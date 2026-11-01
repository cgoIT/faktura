package de.cgoit.faktura.document;

import static de.cgoit.faktura.shared.Texts.blankToNull;
import static de.cgoit.faktura.shared.Texts.contains;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.cgoit.faktura.catalog.CatalogItemInfo;
import de.cgoit.faktura.catalog.CatalogOrder;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.UnitInfo;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.customer.CustomerInfo;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.document.internal.DocumentItemRepository;
import de.cgoit.faktura.document.internal.DocumentRepository;
import de.cgoit.faktura.document.internal.DocumentRepository.DraftValues;
import de.cgoit.faktura.document.internal.DocumentRepository.Header;
import de.cgoit.faktura.document.internal.PaymentRepository;
import de.cgoit.faktura.document.internal.SnapshotCodec;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.TaxProfileInfo;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateService;
import de.cgoit.faktura.tax.TaxProfileService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates and maintains invoice drafts. A draft has no number and no snapshots; it shows live customer data and can
 * be changed or deleted. Any other status is locked: every write goes through {@link #requireDraft(String)} and the
 * SQL itself is limited to drafts as well.
 */
@Service
public class InvoiceService {

    private static final int MAX_PAYMENT_DAYS = 365;

    private final DocumentRepository documents;
    private final DocumentItemRepository items;
    private final PaymentRepository payments;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final UnitService units;
    private final CompanyService companies;
    private final TaxProfileService taxProfiles;
    private final SnapshotCodec codec;
    private final TextTemplateService texts;
    private final InputValidator validator;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public InvoiceService(DocumentRepository documents, DocumentItemRepository items, PaymentRepository payments,
            CustomerService customers,
            CatalogService catalog, UnitService units, CompanyService companies, TaxProfileService taxProfiles,
            SnapshotCodec codec, TextTemplateService texts, InputValidator validator,
            Clock clock, ApplicationEventPublisher events) {
        this.documents = documents;
        this.items = items;
        this.payments = payments;
        this.customers = customers;
        this.catalog = catalog;
        this.units = units;
        this.companies = companies;
        this.taxProfiles = taxProfiles;
        this.codec = codec;
        this.texts = texts;
        this.validator = validator;
        this.clock = clock;
        this.events = events;
    }

    /**
     * Lists invoices, newest first, whose number, subject or customer name contains the search text
     * (case-insensitive, German rules).
     *
     * @param query  search text, blank for all
     * @param status only documents with this display status (an invoice with a cancellation counts as
     *               {@code CANCELLED}), {@code null} for all
     */
    @Transactional(readOnly = true)
    public List<InvoiceSummary> list(String query, DocumentStatus status) {
        var needle = query == null ? "" : query.strip()
                .toLowerCase(Locale.GERMAN);
        var names = customers.search("", CustomerStatus.ALL).stream()
                .collect(Collectors.toMap(CustomerInfo::id, CustomerInfo::name));
        var cancelled = documents.findCancelledInvoiceIds();
        var paid = payments.sumsByDocument();
        var today = LocalDate.now(clock);

        return documents.findAll(DocumentType.INVOICE, DocumentType.CANCELLATION).stream()
                .map(header -> new InvoiceSummary(header.id(), header.type(), displayStatus(header, cancelled),
                        header.number(), header.customerId(), recipientName(header, names), header.subject(),
                        header.issueDate(), header.dueDate(), header.grossTotalCents(),
                        balance(header, cancelled, paid, today), false))
                .filter(row -> status == null || row.status() == status)
                .filter(row -> needle.isEmpty()
                        || contains(row.number(), needle)
                        || contains(row.customerName(), needle)
                        || contains(row.subject(), needle))
                .toList();
    }

    /**
     * Lists the invoices that are overdue today and still open, newest first. Paid and cancelled invoices and
     * cancellation documents never appear; on the due date itself an invoice is not yet overdue.
     */
    @Transactional(readOnly = true)
    public List<InvoiceSummary> listOverdue() {
        return list("", null).stream()
                .filter(row -> row.type() == DocumentType.INVOICE)
                .filter(row -> row.balance() != null && row.balance().overdue() && row.balance().openCents() > 0)
                .toList();
    }

    private static InvoiceBalance balance(Header header, Set<String> cancelled, Map<String, Long> paid,
            LocalDate today) {
        if (header.type() != DocumentType.INVOICE || header.status() != DocumentStatus.FINAL) {
            return null;
        }

        return PaymentCalculator.balance(header.grossTotalCents(), paid.getOrDefault(header.id(), 0L),
                header.dueDate(), cancelled.contains(header.id()), today);
    }

    private String recipientName(Header header, Map<String, String> names) {
        if (header.recipientSnapshot() != null) {
            return codec.recipient(header.recipientSnapshot()).name();
        }

        return names.getOrDefault(header.customerId(), "");
    }

    private static DocumentStatus displayStatus(Header header, Set<String> cancelled) {
        return cancelled.contains(header.id()) ? DocumentStatus.CANCELLED : header.status();
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceInfo> find(String id) {
        return documents.findById(id)
                .map(this::load);
    }

    /**
     * Calculates the totals of an input without saving anything, for live sums in the editor. The total discount is
     * capped at the subtotal; without a given discount the customer's default discount applies, as when saving.
     */
    @Transactional(readOnly = true)
    public Calculation calculate(InvoiceInput input) {
        var discount = input.totalDiscount() != null
                ? input.totalDiscount()
                : Optional.ofNullable(input.customerId())
                        .flatMap(customers::find)
                        .map(InvoiceService::defaultDiscount)
                        .orElse(TotalDiscount.none());
        var calculationItems = input.items().stream()
                .map(InvoiceItemInput::toCalculationItem)
                .toList();

        var day = input.serviceFrom() != null ? input.serviceFrom() : input.issueDate();
        var resolved = resolvedShipping(input.shipping(), day);
        var shipping = resolved.isPresent() && resolved.taxCategory() == null
                ? new ShippingCharge(resolved.name(), resolved.cents(), "", resolved.taxRateBp())
                : resolved;

        requireTaxRules(day, calculationItems, shipping);

        try {
            return InvoiceCalculator.calculate(calculationItems, discount, shipping);
        } catch (ArithmeticException e) {
            throw new RuleViolationException("validation.money");
        }
    }

    /**
     * Proposes an item to add to an invoice: a copy of the catalog entry, or an empty free-text item. Tax category
     * and rate come from the catalog entry and the tax profile valid on the given service day.
     *
     * @param catalogItemId catalog entry to copy, {@code null} for a free-text item
     * @param day           service day (or issue date) that decides which tax profile applies
     * @throws RuleViolationException if the catalog entry is unknown or no tax profile is valid on that day
     */
    @Transactional(readOnly = true)
    public InvoiceItemInput proposeItem(String catalogItemId, LocalDate day) {
        var profile = taxProfiles.findValidOn(day)
                .orElseThrow(() -> new RuleViolationException("invoice.error.noTaxProfile"));
        CatalogItemInfo entry = null;

        if (catalogItemId != null) {
            entry = catalog.find(catalogItemId)
                    .orElseThrow(() -> new RuleViolationException("invoice.error.unknownCatalogItem"));
        }

        var category = entry != null && entry.taxCategory() != null ? entry.taxCategory() : profile.defaultCategory();
        var rate = taxProfiles.carriesRate(category) ? profile.defaultRateBp() : 0;

        if (!profile.allows(category)) {
            throw new RuleViolationException("invoice.error.categoryNotAllowed");
        }

        if (entry == null) {
            return new InvoiceItemInput("", 1000, null, 0, 0, category, rate, null);
        }

        var description = entry.description() == null ? entry.name() : entry.name() + "\n" + entry.description();

        return new InvoiceItemInput(description, 1000, entry.unitCode(), entry.unitPriceCents(), 0, category, rate,
                entry.id());
    }

    /**
     * Creates an invoice draft without a number. Payment term and total discount default to the customer's values
     * when not given.
     *
     * @throws RuleViolationException if the input is invalid
     */
    @Transactional
    public InvoiceInfo createDraft(InvoiceInput input) {
        return createDraft(DocumentType.INVOICE, input, null);
    }

    /**
     * Creates an offer draft without a number. An offer has a validity date instead of a payment term: the given
     * {@code validUntil}, or the issue date plus the default validity of the company.
     *
     * @throws RuleViolationException if the input is invalid
     */
    @Transactional
    public InvoiceInfo createOfferDraft(InvoiceInput input) {
        return createDraft(DocumentType.OFFER, input, null);
    }

    /**
     * Creates an invoice draft that refers to an offer ({@code ref_document_id}). Nothing else links the two: the
     * input is a copy of the offer's content.
     *
     * @throws RuleViolationException if the offer is unknown or the input is invalid
     */
    @Transactional
    public InvoiceInfo createInvoiceDraftForOffer(String offerId, InvoiceInput input) {
        documents.findById(offerId)
                .filter(header -> header.type() == DocumentType.OFFER)
                .orElseThrow(() -> new RuleViolationException("invoice.error.unknown"));

        return createDraft(DocumentType.INVOICE, input, offerId);
    }

    /**
     * Creates an invoice draft as a copy of a finalized invoice, also of a cancelled one: customer, contact person,
     * customer reference, subject, items, total discount and shipping costs are copied. The draft starts today with
     * no service period, the payment term of the customer and the default texts for invoices, because the texts of
     * the original have their placeholders replaced. Nothing links the draft to the original. Prices and tax are
     * checked against the tax profile of today as for any draft; if it no longer allows the tax category of an item,
     * the copy is refused instead of proposing a new one.
     *
     * @throws RuleViolationException if the document is no finalized invoice or the draft is invalid
     */
    @Transactional
    public InvoiceInfo copyAsDraft(String invoiceId) {
        var original = find(invoiceId)
                .filter(found -> found.type() == DocumentType.INVOICE && found.status() == DocumentStatus.FINAL)
                .orElseThrow(() -> new RuleViolationException("invoice.error.copyNotAllowed"));
        var inputItems = original.items().stream()
                .map(item -> new InvoiceItemInput(item.description(), item.quantityThousandths(), item.unitCode(),
                        item.unitPriceCents(), item.discountBp(), item.taxCategory(), item.taxRateBp(),
                        item.catalogItemId()))
                .toList();
        var shipping = original.shipping().isPresent() ? original.shipping() : null;
        var input = new InvoiceInput(original.customerId(), LocalDate.now(clock), null, null, null,
                original.subject(), texts.defaultBody(TextScope.INVOICE, TextKind.HEADER).orElse(null),
                texts.defaultBody(TextScope.INVOICE, TextKind.FOOTER).orElse(null), original.totalDiscount(),
                inputItems, original.contactId(), original.customerReference(), shipping);

        return createDraft(DocumentType.INVOICE, input, null);
    }

    private InvoiceInfo createDraft(DocumentType type, InvoiceInput input, String refDocumentId) {
        var customer = requireCustomer(input.customerId());

        if (customer.archived()) {
            throw new RuleViolationException("invoice.error.customerArchived", "customerId");
        }

        var prepared = prepare(type, input, customer);
        var id = UUID.randomUUID().toString();

        documents.insertDraft(id, type, refDocumentId, prepared.values(), prepared.calculation());
        storeContent(id, prepared);

        return load(requireHeader(id));
    }

    /**
     * Replaces the content of a draft, including all items, whatever its current version is. Use this only where no
     * person edits the draft in parallel; the editor passes the version it read.
     *
     * @throws RuleViolationException if the invoice is unknown, not a draft, or the input is invalid
     */
    @Transactional
    InvoiceInfo updateDraft(String id, InvoiceInput input) {
        return updateDraft(id, input, requireDraft(id).version());
    }

    /**
     * Replaces the content of a draft, including all items, and increments its version.
     *
     * @param expectedVersion the version the caller read when it opened the draft
     * @throws RuleViolationException if the invoice is unknown, not a draft, the input is invalid, or someone saved
     *         the draft after the caller read it ({@code invoice.error.conflict})
     */
    @Transactional
    public InvoiceInfo updateDraft(String id, InvoiceInput input, long expectedVersion) {
        var existing = requireDraft(id);
        var customer = requireCustomer(input.customerId());

        if (customer.archived() && !customer.id().equals(existing.customerId())) {
            throw new RuleViolationException("invoice.error.customerArchived", "customerId");
        }

        var prepared = prepare(existing.type(), input, customer);

        if (!documents.updateDraft(id, prepared.values(), prepared.calculation(), expectedVersion)) {
            throw new RuleViolationException("invoice.error.conflict");
        }

        storeContent(id, prepared);

        return load(requireHeader(id));
    }

    /**
     * Deletes a draft with its items. Finalized documents are never deleted.
     *
     * @throws RuleViolationException if the invoice is unknown or not a draft
     */
    @Transactional
    public void deleteDraft(String id) {
        requireDraft(id);

        if (!documents.deleteDraft(id)) {
            throw new RuleViolationException("document.error.notDraft");
        }

        events.publishEvent(new DraftDeleted(id));
    }

    /**
     * Loads the invoice and requires it to be a draft. Every operation that changes a document starts here.
     *
     * @throws RuleViolationException if the invoice is unknown or not a draft
     */
    Header requireDraft(String id) {
        var header = documents.findById(id)
                .orElseThrow(() -> new RuleViolationException("invoice.error.unknown"));

        if (header.status() != DocumentStatus.DRAFT) {
            throw new RuleViolationException("document.error.notDraft");
        }

        return header;
    }

    private Header requireHeader(String id) {
        return documents.findById(id)
                .orElseThrow();
    }

    private CustomerInfo requireCustomer(String customerId) {
        if (customerId == null || customerId.isBlank()) {
            throw new RuleViolationException("invoice.error.customer", "customerId");
        }

        return customers.find(customerId)
                .orElseThrow(() -> new RuleViolationException("invoice.error.customer", "customerId"));
    }

    /**
     * Checks the items against the tax profile valid on the service day: the category must be allowed by the profile,
     * a category with a rate needs a rate above 0, a category without a rate carries 0, and a profile that charges no
     * tax admits no tax. Without a valid profile nothing is checked here; finalizing requires one.
     */
    private void requireTaxRules(LocalDate day, List<CalculationItem> calculationItems, ShippingCharge shipping) {
        if (day == null) {
            return;
        }

        taxProfiles.findValidOn(day)
                .ifPresent(profile -> requireTaxRules(profile, calculationItems, shipping));
    }

    /**
     * Checks the items and the shipping costs against the given tax profile; a violation of the shipping costs names
     * the path {@code shipping.taxCategory} or {@code shipping.taxRateBp}.
     *
     * @throws RuleViolationException if an item or the shipping costs violate the profile
     */
    void requireTaxRules(TaxProfileInfo profile, List<CalculationItem> calculationItems, ShippingCharge shipping) {
        var itemCount = calculationItems.size();
        var all = withShipping(calculationItems, shipping);

        for (var index = 0; index < all.size(); index++) {
            var item = all.get(index);
            var path = index == itemCount ? "shipping." : "items[" + index + "].";
            var rated = taxProfiles.carriesRate(item.taxCategory());

            if (!profile.allows(item.taxCategory())) {
                throw new RuleViolationException("invoice.error.categoryNotAllowed", path + "taxCategory");
            }

            if (rated && item.taxRateBp() == 0) {
                throw new RuleViolationException("invoice.error.taxRateRequired", path + "taxRateBp");
            }

            if (!rated && item.taxRateBp() != 0) {
                throw new RuleViolationException("invoice.error.taxRateNotAllowed", path + "taxRateBp");
            }

            if (profile.defaultRateBp() == 0 && item.taxRateBp() > 0) {
                throw new RuleViolationException("invoice.error.taxProfileMismatch", path + "taxRateBp");
            }
        }
    }

    /**
     * All catalog entries that can be added to an invoice with the given service day. See
     * {@link #catalogChoices(LocalDate, String, int)}.
     */
    @Transactional(readOnly = true)
    public CatalogChoices catalogChoices(LocalDate day) {
        return catalogChoices(day, "", Integer.MAX_VALUE);
    }

    /**
     * Catalog entries that match the search text (number, name or description, ignoring case) and can be added to an
     * invoice with the given service day, and how many matching active entries are left out because the tax profile
     * of that day does not allow their tax category. An entry without its own category takes the category of the
     * profile and is always allowed.
     *
     * @param query search text, blank for all entries
     * @param limit how many entries to list at most
     */
    @Transactional(readOnly = true)
    public CatalogChoices catalogChoices(LocalDate day, String query, int limit) {
        var found = catalog.search(query, true, CatalogOrder.NAME);
        var profile = taxProfiles.findValidOn(day);

        var allowed = profile.isEmpty()
                ? found
                : found.stream()
                        .filter(entry -> entry.taxCategory() == null || profile.get().allows(entry.taxCategory()))
                        .toList();

        return new CatalogChoices(allowed.stream().limit(limit).toList(), allowed.size(),
                found.size() - allowed.size());
    }

    /**
     * Tax categories an item may have with the given service day: those of the valid tax profile, or all if the
     * profile does not restrict them or none is valid.
     */
    @Transactional(readOnly = true)
    public List<String> allowedCategories(LocalDate day) {
        var all = catalog.taxCategories();

        return taxProfiles.findValidOn(day)
                .filter(profile -> !profile.allowedCategories().isEmpty())
                .map(profile -> all.stream()
                        .filter(profile::allows)
                        .toList())
                .orElse(all);
    }

    /**
     * Validates and calculates an input like saving would, without storing anything. Used for the PDF preview of the
     * current form.
     *
     * @throws RuleViolationException if the input is invalid
     */
    Prepared prepareInput(DocumentType type, InvoiceInput input) {
        return prepare(type, input, requireCustomer(input.customerId()));
    }

    private Prepared prepare(DocumentType type, InvoiceInput rawInput, CustomerInfo customer) {
        var input = normalized(rawInput);

        validator.check(input);

        var from = input.serviceFrom();
        var to = input.serviceTo();

        if ((from == null) != (to == null) || (from != null && from.isAfter(to))) {
            throw new RuleViolationException("invoice.error.servicePeriod", "serviceTo");
        }

        var offer = type == DocumentType.OFFER;
        var dueDate = offer ? null : input.issueDate().plusDays(paymentDays(input, customer));
        var validUntil = offer ? validUntil(input) : null;
        var discount = totalDiscount(input, customer);
        var itemInfos = validatedItems(input.items());
        var calculationItems = itemInfos.stream()
                .map(InvoiceItemInfo::toCalculationItem)
                .toList();

        var shipping = validatedShipping(input.shipping(), from != null ? from : input.issueDate());

        requireTaxRules(from != null ? from : input.issueDate(), calculationItems, shipping);

        var calculation = InvoiceCalculator.calculate(calculationItems, discount, shipping);

        if (discount.type() == TotalDiscount.Type.AMOUNT && discount.value() > calculation.subtotalCents()) {
            throw new RuleViolationException("invoice.error.discountTooHigh");
        }

        var contactId = validatedContact(input.contactId(), customer);

        texts.validate(input.headerText());
        texts.validate(input.footerText());

        var values = new DraftValues(customer.id(), input.issueDate(), from, to, dueDate,
                input.subject(), input.headerText(), input.footerText(), discount, contactId,
                input.customerReference(), shipping, validUntil);

        return new Prepared(values, calculation, itemInfos);
    }

    /**
     * Strips the texts and turns blank ones into {@code null}, item by item and in the same order, so that the index
     * of a violation is the index of the row in the form. A missing item list stays missing for the validator.
     */
    private static InvoiceInput normalized(InvoiceInput input) {
        var normalizedItems = input.items() == null
                ? null
                : input.items().stream()
                        .map(InvoiceService::normalized)
                        .toList();

        return new InvoiceInput(input.customerId(), input.issueDate(), input.serviceFrom(), input.serviceTo(),
                input.paymentDays(), blankToNull(input.subject()), blankToNull(input.headerText()),
                blankToNull(input.footerText()), input.totalDiscount(), normalizedItems, input.contactId(),
                blankToNull(input.customerReference()), normalized(input.shipping()), input.validUntil());
    }

    private static ShippingCharge normalized(ShippingCharge shipping) {
        if (shipping == null || shipping.cents() == 0) {
            return ShippingCharge.none();
        }

        return new ShippingCharge(blankToNull(shipping.name()), shipping.cents(), blankToNull(shipping.taxCategory()),
                shipping.taxRateBp());
    }

    private static InvoiceItemInput normalized(InvoiceItemInput item) {
        if (item == null) {
            return null;
        }

        return new InvoiceItemInput(blankToNull(item.description()), item.quantityThousandths(),
                blankToNull(item.unitCode()), item.unitPriceCents(), item.discountBp(), item.taxCategory(),
                item.taxRateBp(), item.catalogItemId());
    }

    /**
     * Fills an empty tax category of the shipping costs from the profile valid on the service day and checks that the
     * category exists and carries a rate only if it may. Without a valid profile an empty category stays empty.
     */
    private ShippingCharge resolvedShipping(ShippingCharge shipping, LocalDate day) {
        if (shipping == null || !shipping.isPresent()) {
            return ShippingCharge.none();
        }

        if (shipping.taxCategory() != null && !shipping.taxCategory().isBlank()) {
            return shipping;
        }

        return taxProfiles.findValidOn(day)
                .map(profile -> new ShippingCharge(shipping.name(), shipping.cents(), profile.defaultCategory(),
                        taxProfiles.carriesRate(profile.defaultCategory()) ? profile.defaultRateBp() : 0))
                .orElse(shipping);
    }

    private ShippingCharge validatedShipping(ShippingCharge shipping, LocalDate day) {
        var resolved = resolvedShipping(shipping, day);

        if (!resolved.isPresent()) {
            return resolved;
        }

        if (resolved.name() == null) {
            throw new RuleViolationException("invoice.error.shippingName", "shipping.name");
        }

        if (resolved.taxCategory() == null) {
            throw new RuleViolationException("invoice.error.noTaxProfile");
        }

        if (!catalog.taxCategories().contains(resolved.taxCategory())) {
            throw new RuleViolationException("catalog.error.taxCategory", "shipping.taxCategory");
        }

        if (resolved.taxRateBp() != 0 && !taxProfiles.carriesRate(resolved.taxCategory())) {
            throw new RuleViolationException("invoice.error.taxRateNotAllowed", "shipping.taxRateBp");
        }

        return resolved;
    }

    /**
     * The items plus the shipping costs as one more, last item, so that the tax rules apply to them alike.
     */
    private static List<CalculationItem> withShipping(List<CalculationItem> calculationItems, ShippingCharge shipping) {
        if (shipping == null || !shipping.isPresent() || shipping.taxCategory() == null) {
            return calculationItems;
        }

        var all = new ArrayList<>(calculationItems);

        all.add(new CalculationItem(1000, shipping.cents(), 0, shipping.taxCategory(), shipping.taxRateBp()));

        return all;
    }

    private String validatedContact(String contactId, CustomerInfo customer) {
        if (contactId == null || contactId.isBlank()) {
            return null;
        }

        var belongs = customers.findContact(contactId)
                .filter(contact -> contact.customerId().equals(customer.id()))
                .isPresent();

        if (!belongs) {
            throw new RuleViolationException("invoice.error.contact", "contactId");
        }

        return contactId;
    }

    /**
     * The last day an offer is valid: the given date, which must not lie before the issue date, or the issue date
     * plus the default validity of the company.
     */
    private LocalDate validUntil(InvoiceInput input) {
        if (input.validUntil() != null) {
            if (input.validUntil().isBefore(input.issueDate())) {
                throw new RuleViolationException("offer.error.validUntil", "validUntil");
            }

            return input.validUntil();
        }

        var days = companies.find()
                .map(CompanyInfo::offerValidDays)
                .orElseThrow(() -> new RuleViolationException("invoice.error.noCompany"));

        return input.issueDate().plusDays(days);
    }

    private int paymentDays(InvoiceInput input, CustomerInfo customer) {
        var days = input.paymentDays() != null ? input.paymentDays() : customer.paymentDays();

        if (days == null) {
            days = companies.find()
                    .map(company -> company.paymentDays())
                    .orElseThrow(() -> new RuleViolationException("invoice.error.noCompany"));
        }

        if (input.paymentDays() == null && (days < 0 || days > MAX_PAYMENT_DAYS)) {
            throw new RuleViolationException("validation.paymentDays");
        }

        return days;
    }

    private static TotalDiscount totalDiscount(InvoiceInput input, CustomerInfo customer) {
        return input.totalDiscount() != null ? input.totalDiscount() : defaultDiscount(customer);
    }

    private static TotalDiscount defaultDiscount(CustomerInfo customer) {
        return customer.discountBp() == null ? TotalDiscount.none() : TotalDiscount.percent(customer.discountBp());
    }

    /**
     * Resolves the data-dependent rules of the items (unit, tax category and rate) and builds their values. The
     * single-value rules were checked by {@link InputValidator}; a violation names the row as {@code items[i].field}.
     */
    private List<InvoiceItemInfo> validatedItems(List<InvoiceItemInput> inputs) {
        var unitsByCode = units.list().stream()
                .collect(Collectors.toMap(UnitInfo::code, Function.identity()));
        var categories = catalog.taxCategories();
        var result = new ArrayList<InvoiceItemInfo>();

        for (var input : inputs) {
            var path = "items[" + result.size() + "].";

            if (!categories.contains(input.taxCategory())) {
                throw new RuleViolationException("catalog.error.taxCategory", path + "taxCategory");
            }

            if (input.taxRateBp() != 0 && !taxProfiles.carriesRate(input.taxCategory())) {
                throw new RuleViolationException("invoice.error.taxRateNotAllowed", path + "taxRateBp");
            }

            var unit = input.unitCode() == null ? null : unitsByCode.get(input.unitCode());

            if (input.unitCode() != null && unit == null) {
                throw new RuleViolationException("invoice.error.unit", path + "unitCode");
            }

            var lineTotal = InvoiceCalculator.lineTotal(input.toCalculationItem());

            result.add(new InvoiceItemInfo(null, result.size() + 1, input.description(), input.quantityThousandths(),
                    unit == null ? null : unit.code(), unit == null ? null : unit.name(),
                    unit == null ? null : unit.namePlural(),
                    unit == null ? null : unit.uneceCode(), input.unitPriceCents(), input.discountBp(),
                    input.taxCategory(), input.taxRateBp(), lineTotal, input.catalogItemId()));
        }

        return result;
    }

    private void storeContent(String id, Prepared prepared) {
        items.deleteOfDraft(id);

        for (var item : prepared.items()) {
            items.insert(id, item);
        }

        documents.replaceTaxTotals(id, prepared.calculation().taxTotals());
    }

    private InvoiceInfo load(Header header) {
        return new InvoiceInfo(header.id(), header.status(), header.number(), header.customerId(),
                header.issueDate(), header.serviceFrom(), header.serviceTo(), header.dueDate(), header.subject(),
                header.headerText(), header.footerText(), header.totalDiscount(), items.findByDocument(header.id()),
                header.netTotalCents(), header.taxTotalCents(), header.grossTotalCents(),
                documents.findTaxTotals(header.id()), header.type(), header.refDocumentId(),
                documents.findCancellationOf(header.id()).orElse(null),
                header.recipientSnapshot() == null ? null : codec.recipient(header.recipientSnapshot()).name(),
                header.contactId(), header.createdBy(), header.version(), header.customerReference(),
                header.shipping(), header.validUntil());
    }

    record Prepared(DraftValues values, Calculation calculation, List<InvoiceItemInfo> items) {
    }
}
