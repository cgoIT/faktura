package de.cgoit.faktura.dunning;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.cgoit.faktura.archive.ArchiveFileType;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.DunningBasis;
import de.cgoit.faktura.document.InvoiceBalance;
import de.cgoit.faktura.document.InvoiceLetterService;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.InvoiceSummary;
import de.cgoit.faktura.document.MailBasis;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.dunning.internal.DunningRepository;
import de.cgoit.faktura.numbering.NumberScope;
import de.cgoit.faktura.numbering.NumberService;
import de.cgoit.faktura.render.DunningRenderModel;
import de.cgoit.faktura.render.DunningRenderer;
import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.shared.MoneyFormatter;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.Placeholder;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suggests dunning letters for overdue invoices and creates them. The system only suggests: a letter is created when
 * the user asks for it, and nothing is sent. A letter is a new record with its own number and PDF; the invoice stays
 * untouched. Creating takes one transaction, so a failure leaves neither a letter nor a used number.
 */
@Service
public class DunningService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final DunningRepository dunnings;
    private final DunningLevelService levels;
    private final InvoiceService invoices;
    private final InvoiceLetterService letters;
    private final PaymentService payments;
    private final NumberService numbers;
    private final TextTemplateService texts;
    private final DunningRenderer renderer;
    private final ArchiveService archive;
    private final AuditService audit;
    private final MoneyFormatter money;
    private final Clock clock;

    public DunningService(DunningRepository dunnings, DunningLevelService levels, InvoiceService invoices,
            InvoiceLetterService letters, PaymentService payments, NumberService numbers, TextTemplateService texts,
            DunningRenderer renderer, ArchiveService archive, AuditService audit, MoneyFormatter money, Clock clock) {
        this.dunnings = dunnings;
        this.levels = levels;
        this.invoices = invoices;
        this.letters = letters;
        this.payments = payments;
        this.numbers = numbers;
        this.texts = texts;
        this.renderer = renderer;
        this.archive = archive;
        this.audit = audit;
        this.money = money;
        this.clock = clock;
    }

    /**
     * Lists the overdue invoices for which a letter is suggested today, the longest overdue first. An invoice drops
     * out of the list as soon as it is paid, cancelled, or while the term of its last letter is still running.
     */
    @Transactional(readOnly = true)
    public List<DunningSuggestion> suggestions() {
        var today = LocalDate.now(clock);
        var allLevels = levels.list();
        var issued = dunnings.findAll()
                .stream()
                .collect(Collectors.groupingBy(DunningInfo::invoiceId));

        return overdueInvoices().stream()
                .map(row -> suggestion(row, allLevels, issued.getOrDefault(row.id(), List.of()), today))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparingLong(DunningSuggestion::daysOverdue).reversed())
                .toList();
    }

    /**
     * Lists the created letters, newest first; letters of invoices that are paid or cancelled come last.
     */
    @Transactional(readOnly = true)
    public List<DunningRow> list() {
        var summaries = invoices.list("", null)
                .stream()
                .collect(Collectors.toMap(InvoiceSummary::id, Function.identity()));

        return dunnings.findAll()
                .stream()
                .map(dunning -> row(dunning, summaries.get(dunning.invoiceId())))
                .sorted(Comparator.comparing(DunningRow::settled))
                .toList();
    }

    /**
     * Lists the letters of one invoice, lowest level first.
     */
    @Transactional(readOnly = true)
    public List<DunningInfo> listFor(String invoiceId) {
        return dunnings.findByInvoice(invoiceId);
    }

    /**
     * Lists the levels that can still be created for an invoice today: active, not yet issued, and only while the
     * invoice is overdue and open.
     */
    @Transactional(readOnly = true)
    public List<DunningLevelInfo> availableLevels(String invoiceId) {
        var balance = payments.balance(invoiceId);

        if (balance == null || !balance.overdue() || balance.openCents() <= 0) {
            return List.of();
        }

        var issuedLevels = dunnings.findByInvoice(invoiceId)
                .stream()
                .map(DunningInfo::level)
                .toList();

        return levels.list().stream()
                .filter(DunningLevelInfo::active)
                .filter(level -> !issuedLevels.contains(level.level()))
                .toList();
    }

    /**
     * Describes the letter as it would be created today, for the confirmation dialog. Nothing is stored.
     *
     * @throws RuleViolationException if no letter can be created for the invoice and level
     */
    @Transactional(readOnly = true)
    public DunningReview review(String invoiceId, String levelId) {
        var plan = plan(invoiceId, levelId);
        var basis = plan.basis();

        return new DunningReview(invoiceId, basis.number(), basis.recipient().name(), basis.issueDate(),
                basis.dueDate(), plan.balance().daysOverdue(), basis.grossCents(), plan.level(), plan.amounts(),
                plan.newDueDate());
    }

    /**
     * Renders the letter as it would be created today, without number, and stores nothing.
     *
     * @throws RuleViolationException if no letter can be created for the invoice and level
     */
    @Transactional(readOnly = true)
    public byte[] preview(String invoiceId, String levelId) {
        var plan = plan(invoiceId, levelId);

        return renderer.renderPdf(model(plan, null, true));
    }

    /**
     * Creates a dunning letter: takes the next number, renders the PDF, archives it and stores the record, all in
     * one transaction. The invoice is not changed.
     *
     * @throws RuleViolationException if the invoice is not overdue and open, the level is switched off or already
     *                                issued for this invoice
     */
    @Transactional
    public DunningInfo create(String invoiceId, String levelId) {
        var plan = plan(invoiceId, levelId);
        var number = numbers.next(NumberScope.DUNNING, plan.today());
        var id = UUID.randomUUID().toString();
        var dunning = new DunningInfo(id, invoiceId, plan.level().level(), plan.level().name(), number, plan.today(),
                plan.amounts().openCents(), plan.amounts().feeCents(), plan.amounts().interestCents(),
                plan.newDueDate());
        var pdf = renderer.renderPdf(model(plan, number, false));

        dunnings.insert(dunning, Instant.now(clock).toString());
        archive.store(ArchiveOwnerType.DUNNING, id, ArchiveFileType.PDF,
                "dunning/" + plan.today().getYear() + "/" + ArchiveService.fileName(number, id) + ".pdf", pdf);
        audit.record(AuditObjectType.DUNNING, id, AuditAction.DUNNING_CREATE,
                Map.of("number", number, "invoiceNumber", plan.basis().number(), "level",
                        String.valueOf(plan.level().level())));

        return dunning;
    }

    /**
     * Returns the archived PDF of a letter, hash-verified.
     */
    @Transactional(readOnly = true)
    public Optional<StoredLetter> findPdf(String dunningId) {
        return dunnings.findById(dunningId)
                .flatMap(dunning -> archive.findByOwner(ArchiveOwnerType.DUNNING, dunningId)
                        .stream()
                        .filter(file -> file.type() == ArchiveFileType.PDF)
                        .findFirst()
                        .map(file -> new StoredLetter(dunning.number() + ".pdf", archive.read(file))));
    }

    /**
     * Returns the data the mail module needs for a dunning letter: the parties and invoice values from the snapshots of
     * the invoice plus the values of the letter as it was created. Empty for an unknown letter.
     */
    @Transactional(readOnly = true)
    public Optional<MailBasis> mailBasis(String dunningId) {
        return dunnings.findById(dunningId)
                .flatMap(dunning -> letters.mailBasis(dunning.invoiceId())
                        .map(basis -> {
                            var values = new EnumMap<Placeholder, String>(Placeholder.class);

                            values.putAll(basis.placeholders());
                            values.put(Placeholder.DUNNING_NUMBER, RichText.escape(dunning.number()));
                            values.put(Placeholder.DUNNING_LEVEL, RichText.escape(dunning.levelName()));
                            values.put(Placeholder.OPEN_AMOUNT, money.format(dunning.openCents()));
                            values.put(Placeholder.DUNNING_FEE, dunning.feeCents() == 0
                                    ? "" : money.format(dunning.feeCents()));
                            values.put(Placeholder.INTEREST, dunning.interestCents() == 0
                                    ? "" : money.format(dunning.interestCents()));
                            values.put(Placeholder.TOTAL_CLAIM, money.format(dunning.totalCents()));
                            values.put(Placeholder.NEW_DUE_DATE, DATE.format(dunning.newDueDate()));
                            values.put(Placeholder.DAYS_OVERDUE, basis.dueDate() == null
                                    ? "" : String.valueOf(ChronoUnit.DAYS.between(basis.dueDate(), dunning.date())));

                            return new MailBasis(dunning.number(), basis.dueDate(), basis.recipientEmail(),
                                    basis.contactEmail(), values);
                        }));
    }

    /**
     * A stored PDF with the file name it should be offered under.
     */
    public record StoredLetter(String filename, byte[] content) {
    }

    private Optional<DunningSuggestion> suggestion(InvoiceSummary row, List<DunningLevelInfo> allLevels,
            List<DunningInfo> issued, LocalDate today) {
        var balance = row.balance();

        return DunningPlanner.suggest(allLevels, issued, balance.daysOverdue(), today)
                .map(level -> new DunningSuggestion(row.id(), row.number(), row.customerName(), row.dueDate(),
                        balance.daysOverdue(), level, DunningPlanner.amounts(level, balance.openCents(),
                                balance.daysOverdue())));
    }

    private List<InvoiceSummary> overdueInvoices() {
        return invoices.listOverdue();
    }

    private DunningRow row(DunningInfo dunning, InvoiceSummary invoice) {
        return new DunningRow(dunning, invoice == null ? "" : invoice.number(),
                invoice == null ? "" : invoice.customerName(), invoice == null ? null : invoice.balance());
    }

    private Plan plan(String invoiceId, String levelId) {
        var today = LocalDate.now(clock);
        var balance = payments.balance(invoiceId);

        if (balance == null || !balance.overdue() || balance.openCents() <= 0) {
            throw new RuleViolationException("dunning.error.notDue");
        }

        var level = levels.find(levelId)
                .orElseThrow(() -> new RuleViolationException("dunning.error.level"));

        if (!level.active()) {
            throw new RuleViolationException("dunning.error.inactive");
        }

        var alreadyIssued = dunnings.findByInvoice(invoiceId)
                .stream()
                .anyMatch(dunning -> dunning.level() == level.level());

        if (alreadyIssued) {
            throw new RuleViolationException("dunning.error.issued");
        }

        var basis = letters.dunningBasis(invoiceId)
                .orElseThrow(() -> new RuleViolationException("dunning.error.notDue"));
        var amounts = DunningPlanner.amounts(level, balance.openCents(), balance.daysOverdue());

        return new Plan(today, level, amounts, balance, basis, today.plusDays(level.newDueDays()));
    }

    private DunningRenderModel model(Plan plan, String number, boolean draft) {
        var values = new EnumMap<Placeholder, String>(Placeholder.class);
        var level = plan.level();
        var amounts = plan.amounts();

        values.putAll(plan.basis().placeholders());
        values.put(Placeholder.DUNNING_LEVEL, RichText.escape(level.name()));
        values.put(Placeholder.OPEN_AMOUNT, money.format(amounts.openCents()));
        values.put(Placeholder.DUNNING_FEE, amounts.feeCents() == 0 ? "" : money.format(amounts.feeCents()));
        values.put(Placeholder.INTEREST, amounts.interestCents() == 0 ? "" : money.format(amounts.interestCents()));
        values.put(Placeholder.TOTAL_CLAIM, money.format(amounts.totalCents()));
        values.put(Placeholder.NEW_DUE_DATE, DATE.format(plan.newDueDate()));
        values.put(Placeholder.DAYS_OVERDUE, String.valueOf(plan.balance().daysOverdue()));

        var basis = plan.basis();
        var paid = basis.grossCents() - amounts.openCents();

        return new DunningRenderModel(draft, number, plan.today(), level.name(), basis.sender(), basis.recipient(),
                basis.customerNumber(), basis.number(), basis.issueDate(), basis.dueDate(), basis.grossCents(),
                Math.max(0, paid), amounts.openCents(), amounts.feeCents(), amounts.interestCents(),
                amounts.totalCents(), plan.newDueDate(), texts.expand(level.headerText(), values),
                texts.expand(level.footerText(), values), basis.account());
    }

    private record Plan(LocalDate today, DunningLevelInfo level, DunningAmounts amounts, InvoiceBalance balance,
            DunningBasis basis, LocalDate newDueDate) {
    }
}
