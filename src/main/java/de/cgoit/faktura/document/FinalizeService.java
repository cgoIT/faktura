package de.cgoit.faktura.document;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.cgoit.faktura.archive.ArchiveFileType;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.document.internal.DocumentItemRepository;
import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.document.internal.DocumentRepository.DraftValues;
import de.cgoit.faktura.document.internal.DocumentRepository.Header;
import de.cgoit.faktura.document.internal.DocumentRepository.Snapshots;
import de.cgoit.faktura.document.internal.EInvoiceMapper;
import de.cgoit.faktura.document.internal.RecipientSnapshot;
import de.cgoit.faktura.document.internal.PlaceholderValues;
import de.cgoit.faktura.document.internal.RenderModelFactory;
import de.cgoit.faktura.document.internal.SenderSnapshot;
import de.cgoit.faktura.document.internal.SnapshotCodec;
import de.cgoit.faktura.document.internal.TaxSnapshot;
import de.cgoit.faktura.einvoice.EInvoiceException;
import de.cgoit.faktura.einvoice.EInvoiceService;
import de.cgoit.faktura.numbering.NumberScope;
import de.cgoit.faktura.numbering.NumberService;
import de.cgoit.faktura.render.InvoiceRenderer;
import de.cgoit.faktura.render.RenderModel;
import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.shared.Iban;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.TaxProfileInfo;
import de.cgoit.faktura.tax.TaxProfileService;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finalizes invoice drafts and cancels finalized invoices. Finalizing runs as one transaction: tax profile and
 * snapshots, number, totals, PDF/A, archive file with SHA-256, status {@code FINAL} and audit entry. If any step
 * fails the transaction rolls back, the invoice stays a draft and the number stays free. A finalized document is
 * never changed again; a cancellation is a new document that refers to the invoice.
 */
@Service
public class FinalizeService {

    private static final Logger LOG = LoggerFactory.getLogger(FinalizeService.class);

    private static final String TRIAL_NUMBER = "DRAFT";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final InvoiceService invoices;
    private final DocumentRepository documents;
    private final DocumentItemRepository items;
    private final CompanyService companies;
    private final CustomerService customers;
    private final TaxProfileService taxProfiles;
    private final NumberService numbers;
    private final InvoiceRenderer renderer;
    private final EInvoiceService einvoices;
    private final EInvoiceMapper einvoiceMapper;
    private final ArchiveService archive;
    private final AuditService audit;
    private final RenderModelFactory models;
    private final SnapshotCodec codec;
    private final TextTemplateService texts;
    private final PlaceholderValues placeholders;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public FinalizeService(InvoiceService invoices, DocumentRepository documents, DocumentItemRepository items,
            CompanyService companies, CustomerService customers, TaxProfileService taxProfiles,
            NumberService numbers, InvoiceRenderer renderer, EInvoiceService einvoices, EInvoiceMapper einvoiceMapper,
            ArchiveService archive, AuditService audit,
            RenderModelFactory models, SnapshotCodec codec, TextTemplateService texts,
            PlaceholderValues placeholders, Clock clock, ApplicationEventPublisher events) {
        this.invoices = invoices;
        this.documents = documents;
        this.items = items;
        this.companies = companies;
        this.customers = customers;
        this.taxProfiles = taxProfiles;
        this.numbers = numbers;
        this.renderer = renderer;
        this.einvoices = einvoices;
        this.einvoiceMapper = einvoiceMapper;
        this.archive = archive;
        this.audit = audit;
        this.models = models;
        this.codec = codec;
        this.texts = texts;
        this.placeholders = placeholders;
        this.clock = clock;
        this.events = events;
    }

    /**
     * A stored PDF with the file name it should be offered under.
     */
    public record StoredPdf(String filename, byte[] content) {
    }

    /**
     * Finalizes an invoice or offer draft. An offer runs through the same steps but gets no e-invoice, whatever the
     * customer's e-invoice mode says, and takes its number from the offer number range. Positions with tax are
     * rejected under a profile that charges none; positions with rate 0 are allowed under any profile (exempt or
     * zero-rated categories). The tax profile valid on the first service day (or the issue date) is resolved and
     * copied together with sender and recipient into the document, the number is assigned, the totals are stored and
     * the PDF is archived. Afterwards the document is locked.
     *
     * @throws RuleViolationException if the invoice is unknown, not a draft, has no items, or no tax profile or
     *                                company applies
     */
    @Transactional
    public InvoiceInfo finalizeInvoice(String id) {
        var header = invoices.requireDraft(id);

        if (header.type() == DocumentType.CANCELLATION) {
            throw new RuleViolationException("document.error.notDraft");
        }

        var draftItems = items.findByDocument(id);

        if (draftItems.isEmpty()) {
            throw new RuleViolationException(header.type() == DocumentType.OFFER
                    ? "offer.error.noItems"
                    : "invoice.error.noItems");
        }

        var prepared = prepare(header);

        var calculationItems = draftItems.stream()
                .map(InvoiceItemInfo::toCalculationItem)
                .toList();

        invoices.requireTaxRules(prepared.profile(), calculationItems, header.shipping());

        var calculation = InvoiceCalculator.calculate(calculationItems, header.totalDiscount(), header.shipping());

        var finalized = complete(header, draftItems, calculation, prepared, null);

        if (header.type() == DocumentType.INVOICE) {
            events.publishEvent(new InvoiceFinalized(id));
        }

        return finalized;
    }

    /**
     * Cancels a finalized invoice by creating a finalized cancellation document with its own number. Items and
     * totals are the negated copies of the invoice, sender, recipient and tax profile are copied from it. The
     * invoice itself stays unchanged; it counts as cancelled because a cancellation refers to it.
     *
     * @return the cancellation document
     * @throws RuleViolationException if the invoice is unknown, not finalized, or already cancelled
     */
    @Transactional
    public InvoiceInfo cancelInvoice(String invoiceId) {
        var original = documents.findById(invoiceId)
                .orElseThrow(() -> new RuleViolationException("invoice.error.unknown"));

        if (original.type() != DocumentType.INVOICE || original.status() != DocumentStatus.FINAL) {
            throw new RuleViolationException("document.error.notCancellable");
        }

        if (documents.findCancellationOf(invoiceId).isPresent()) {
            throw new RuleViolationException("document.error.alreadyCancelled");
        }

        var snapshots = documents.findSnapshots(invoiceId)
                .orElseThrow();
        var originalItems = items.findByDocument(invoiceId);
        var negatedItems = originalItems.stream()
                .map(FinalizeService::negate)
                .toList();
        var subtotal = originalItems.stream()
                .mapToLong(InvoiceItemInfo::lineTotalCents)
                .sum();
        var negatedLineTotals = negatedItems.stream()
                .map(InvoiceItemInfo::lineTotalCents)
                .toList();
        var shipping = original.shipping();
        var discount = subtotal - (original.netTotalCents() - shipping.cents());
        var calculation = new Calculation(negatedLineTotals,
                -subtotal, -discount, -shipping.cents(), -original.netTotalCents(),
                -original.taxTotalCents(), -original.grossTotalCents(),
                documents.findTaxTotals(invoiceId).stream()
                        .map(total -> new TaxTotal(total.category(), total.rateBp(), -total.basisCents(),
                                -total.taxCents()))
                        .toList());
        var id = UUID.randomUUID().toString();
        var values = new DraftValues(original.customerId(), LocalDate.now(clock), original.serviceFrom(),
                original.serviceTo(), null, null, null, null, TotalDiscount.none(), null, original.customerReference(),
                new ShippingCharge(shipping.name(), -shipping.cents(), shipping.taxCategory(), shipping.taxRateBp()),
                null);

        documents.insertDraft(id, DocumentType.CANCELLATION, invoiceId, values, calculation);

        for (var item : negatedItems) {
            items.insert(id, item);
        }

        var draft = documents.findById(id)
                .orElseThrow();
        var prepared = new Prepared(codec.sender(snapshots), codec.recipient(snapshots), codec.tax(snapshots),
                snapshots);
        var cancellation = complete(draft, negatedItems, calculation, prepared, original);

        audit.record(AuditObjectType.DOCUMENT, invoiceId, AuditAction.CANCEL,
                Map.of("cancellationNumber", cancellation.number()));

        return cancellation;
    }

    /**
     * Renders a preview of the given form content as PDF. Nothing is stored or changed, the document has no number
     * and is marked as a draft. The preview shows what the form contains now, not what was saved last.
     *
     * @throws RuleViolationException if the invoice is unknown or not a draft, or the input is invalid
     */
    @Transactional(readOnly = true)
    public byte[] previewDraft(String id, InvoiceInput input) {
        return renderer.renderPdf(draft(id, input).model());
    }

    /**
     * Shows what finalizing the given form content would produce, so that the user can check it first. Nothing is
     * stored, no number is taken. The same rules as for finalizing apply. For a customer with e-invoice a trial run
     * creates and validates the XML, so that problems show up before the user confirms.
     *
     * @throws RuleViolationException if the invoice is unknown or not a draft, the input is invalid, or no tax profile
     *                                or company applies
     */
    @Transactional(readOnly = true)
    public FinalizeReview review(String id, InvoiceInput input) {
        var draft = draft(id, input);
        var recipient = draft.prepared().recipient();
        var values = draft.content().values();
        var failed = false;
        var rules = List.<String>of();
        var einvoice = draft.header().type() != DocumentType.OFFER;

        if (einvoice && recipient.einvoiceMode() == EInvoiceMode.XRECHNUNG) {
            throw new RuleViolationException("customer.error.einvoiceMode");
        }

        if (einvoice && recipient.einvoiceMode() == EInvoiceMode.ZUGFERD) {
            try {
                var data = einvoiceMapper.map(draft.header(), TRIAL_NUMBER, draft.content().items(),
                        draft.content().calculation(), draft.prepared().sender(), recipient, draft.prepared().tax(),
                        null);

                einvoices.embed(renderer.renderPdf(draft.model()), data);
            } catch (EInvoiceException e) {
                failed = true;
                rules = e.rules();
            }
        }

        var address = Stream.of(recipient.street(), join(recipient.zip(), recipient.city()), recipient.country())
                .filter(line -> line != null && !line.isBlank())
                .toList();

        return new FinalizeReview(recipient.number(), recipient.name(), address, recipient.contactName(),
                recipient.einvoiceMode(), draft.prepared().tax().regime(), draft.prepared().tax().exemptionText(),
                values.issueDate(), values.serviceFrom(), values.serviceTo(), values.dueDate(), values.subject(),
                draft.headerText(), draft.footerText(), draft.content().items(), draft.content().calculation(),
                draft.content().calculation().discountCents(), values.customerReference(), values.shipping().name(),
                failed, rules, values.validUntil());
    }

    private Draft draft(String id, InvoiceInput input) {
        var type = invoices.requireDraft(id).type();
        var content = invoices.prepareInput(type, input);
        var values = content.values();
        var calculation = content.calculation();
        var header = new Header(id, type, DocumentStatus.DRAFT, null, values.customerId(),
                values.issueDate(), values.serviceFrom(), values.serviceTo(), values.dueDate(), values.subject(),
                values.headerText(), values.footerText(), values.totalDiscount(), calculation.netCents(),
                calculation.taxCents(), calculation.grossCents(), null, null, values.contactId(), null, 0,
                values.customerReference(), values.shipping(), values.validUntil());
        var prepared = prepare(header);
        var filled = placeholders.build(header, prepared.sender(), prepared.recipient(), calculation.grossCents(), null);
        var headerText = texts.expand(header.headerText(), filled);
        var footerText = texts.expand(header.footerText(), filled);
        var model = models.build(header, null, true, content.items(), calculation, prepared.sender(),
                prepared.recipient(), prepared.tax(), null, headerText, footerText);

        return new Draft(header, prepared, content, headerText, footerText, model);
    }

    private static String join(String zip, String city) {
        return Stream.of(zip, city)
                .filter(part -> part != null && !part.isBlank())
                .collect(Collectors.joining(" "));
    }

    /**
     * Returns the archived PDF of a finalized document, verified against its stored hash.
     *
     * @throws de.cgoit.faktura.archive.ArchiveIntegrityException if the file is missing or was changed
     */
    @Transactional(readOnly = true)
    public Optional<StoredPdf> findPdf(String documentId) {
        var header = documents.findById(documentId);

        if (header.isEmpty() || header.get().number() == null) {
            return Optional.empty();
        }

        return archive.findByOwner(ArchiveOwnerType.DOCUMENT, documentId).stream()
                .filter(file -> file.type() == ArchiveFileType.PDF)
                .findFirst()
                .map(file -> new StoredPdf(header.get().number() + ".pdf", archive.read(file)));
    }

    private InvoiceInfo complete(Header header, List<InvoiceItemInfo> documentItems, Calculation calculation,
            Prepared prepared, Header referenced) {
        var number = numbers.next(scope(header.type()), header.issueDate());

        documents.replaceTaxTotals(header.id(), calculation.taxTotals());

        var filled = placeholders.build(header, prepared.sender(), prepared.recipient(), calculation.grossCents(), number);
        var headerText = texts.expand(header.headerText(), filled);
        var footerText = texts.expand(header.footerText(), filled);
        var model = models.build(header, number, false, documentItems, calculation, prepared.sender(),
                prepared.recipient(), prepared.tax(), referenced, headerText, footerText);
        var pdf = renderer.renderPdf(model);

        var withEinvoice = header.type() != DocumentType.OFFER;

        if (withEinvoice && prepared.recipient().einvoiceMode() == EInvoiceMode.XRECHNUNG) {
            throw new RuleViolationException("customer.error.einvoiceMode");
        }

        if (withEinvoice && prepared.recipient().einvoiceMode() == EInvoiceMode.ZUGFERD) {
            var data = einvoiceMapper.map(header, number, documentItems, calculation, prepared.sender(),
                    prepared.recipient(), prepared.tax(), referenced);

            try {
                var einvoice = einvoices.embed(pdf, data);

                pdf = einvoice.pdf();
                archive.store(ArchiveOwnerType.DOCUMENT, header.id(), ArchiveFileType.XML,
                        archivePath(header, number, "xml"), einvoice.xml());
            } catch (EInvoiceException e) {
                LOG.warn("E-invoice of document {} rejected: {}", header.id(), e.getMessage());

                throw new RuleViolationException("einvoice.error.validation", e.rules());
            }
        }

        archive.store(ArchiveOwnerType.DOCUMENT, header.id(), ArchiveFileType.PDF, archivePath(header, number, "pdf"),
                pdf);

        if (!documents.finalizeDraft(header.id(), number, calculation, prepared.snapshots(), headerText,
                footerText)) {
            throw new RuleViolationException("document.error.notDraft");
        }

        audit.record(AuditObjectType.DOCUMENT, header.id(), AuditAction.FINALIZE,
                Map.of("number", number, "type", header.type().name()));

        return invoices.find(header.id())
                .orElseThrow();
    }

    private Prepared prepare(Header header) {
        var day = header.serviceFrom() != null ? header.serviceFrom() : header.issueDate();
        var profile = taxProfiles.findValidOn(day)
                .orElseThrow(() -> new RuleViolationException("invoice.error.noTaxProfile"));

        if (header.serviceTo() != null && !profile.isValidOn(header.serviceTo())) {
            throw new RuleViolationException("invoice.error.servicePeriodTaxChange");
        }

        var company = companies.find()
                .orElseThrow(() -> new RuleViolationException("invoice.error.noCompany"));
        var customer = customers.find(header.customerId())
                .orElseThrow(() -> new RuleViolationException("invoice.error.customer"));
        var sender = SenderSnapshot.of(company);
        var contact = header.contactId() == null
                ? null
                : customers.findContact(header.contactId())
                        .filter(found -> found.customerId().equals(customer.id()))
                        .orElse(null);
        var recipient = RecipientSnapshot.of(customer, contact);
        var tax = TaxSnapshot.of(profile, taxProfiles.categoryReasons());

        return new Prepared(sender, recipient, tax, codec.write(sender, recipient, tax), profile);
    }

    private static NumberScope scope(DocumentType type) {
        return switch (type) {
            case CANCELLATION -> NumberScope.CANCELLATION;
            case OFFER -> NumberScope.OFFER;
            case INVOICE -> NumberScope.INVOICE;
        };
    }

    /**
     * Archive path {@code <year>/<number>.<extension>}. A number with characters that are unsafe in a path gets a
     * part of the document id appended, so two different numbers never end up on the same path.
     */
    private static String archivePath(Header header, String number, String extension) {
        return header.issueDate().getYear() + "/" + ArchiveService.fileName(number, header.id()) + "." + extension;
    }

    private static InvoiceItemInfo negate(InvoiceItemInfo item) {
        return new InvoiceItemInfo(null, item.position(), item.description(), -item.quantityThousandths(),
                item.unitCode(), item.unitName(), item.unitNamePlural(), item.unitUneceCode(), item.unitPriceCents(), item.discountBp(),
                item.taxCategory(), item.taxRateBp(), -item.lineTotalCents(), item.catalogItemId());
    }

    /**
     * A draft as it would be finalized now: header, snapshots, expanded texts and the model of the preview PDF.
     */
    private record Draft(Header header, Prepared prepared, InvoiceService.Prepared content, String headerText,
            String footerText, RenderModel model) {
    }

    /**
     * Snapshots of a document being finalized, as objects and as the JSON that is stored.
     */
    private record Prepared(SenderSnapshot sender, RecipientSnapshot recipient, TaxSnapshot tax, Snapshots snapshots,
            TaxProfileInfo profile) {

        Prepared(SenderSnapshot sender, RecipientSnapshot recipient, TaxSnapshot tax, Snapshots snapshots) {
            this(sender, recipient, tax, snapshots, null);
        }
    }
}
