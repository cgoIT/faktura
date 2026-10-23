package de.cgoit.faktura.dunning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.TestArchive;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.PaymentInput;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.render.DunningRenderModel;
import de.cgoit.faktura.render.DunningRenderer;
import de.cgoit.faktura.shared.RuleViolationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SqliteTest
class DunningServiceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private PaymentService payments;

    @Autowired
    private DunningService dunnings;

    @Autowired
    private DunningLevelService levels;

    @Autowired
    private CustomerService customers;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private AuditService audit;

    @Autowired
    private JdbcClient jdbc;

    @MockitoSpyBean
    private DunningRenderer renderer;

    private String customerId;

    private List<DunningLevelInfo> originalLevels;

    @BeforeEach
    void setUp() {
        cleanUp();
        originalLevels = levels.list();
        jdbc.sql("""
                INSERT INTO company (id, name, owner, street, zip, city, country, iban, bic, bank_name, payment_days,
                                     created_at)
                VALUES ('c1', 'Beispiel Nebengewerbe', 'Erika Beispiel', 'Musterweg 1', '12345', 'Beispielstadt', 'DE',
                        'DE02120300000000202051', 'BYLADEM1001', 'Testbank', 14, '2026-01-01T00:00:00Z')
                """).update();
        jdbc.sql("""
                INSERT INTO tax_profile (id, company_id, valid_from, valid_to, regime, default_rate_bp,
                                         default_category, exemption_text)
                VALUES ('p1', 'c1', '2020-01-01', NULL, 'SMALL_BUSINESS', 0, 'E', 'Gemäß § 19 UStG keine Umsatzsteuer.')
                """).update();
        customerId = customers.create(new CustomerInput("Muster GmbH", "Teststraße 1", "54321", "Musterhausen", "DE",
                null, null, null, null, null)).id();
    }

    @AfterEach
    void cleanUp() {
        if (originalLevels != null) {
            originalLevels.forEach(level -> levels.update(level.id(), new DunningLevelInput(level.level(), level.name(),
                    level.daysAfterDue(), level.feeCents(), level.interestBp(), level.newDueDays(), level.headerText(),
                    level.footerText(), level.active())));
        }

        jdbc.sql("DELETE FROM dunning").update();
        jdbc.sql("DELETE FROM payment WHERE reverses_payment_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM payment").update();
        jdbc.sql("DELETE FROM audit_log").update();
        jdbc.sql("DELETE FROM file").update();
        jdbc.sql("DELETE FROM document_tax_total").update();
        jdbc.sql("DELETE FROM document_item").update();
        jdbc.sql("DELETE FROM document").update();
        jdbc.sql("DELETE FROM customer_contact").update();
        jdbc.sql("DELETE FROM customer").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
        jdbc.sql("DELETE FROM number_sequence").update();
        TestArchive.clear();
    }

    /** Finalized invoice of 1,000.00 EUR, issued {@code daysAgo} days ago, due 14 days later. */
    private InvoiceInfo invoice(int daysAgo) {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY.minusDays(daysAgo), null, null, 14, "Betreff",
                "Kopf", "Fuß", TotalDiscount.none(), List.of(item)));

        return finalizer.finalizeInvoice(draft.id());
    }

    private String levelId(int level) {
        return levels.list().stream()
                .filter(info -> info.level() == level)
                .findFirst()
                .orElseThrow()
                .id();
    }

    @Test
    void defaultLevelsExistWithFeeAndInterestSwitchedOff() {
        assertThat(levels.list()).extracting(DunningLevelInfo::name)
                .containsExactly("Zahlungserinnerung", "1. Mahnung", "2. Mahnung");
        assertThat(levels.list()).allSatisfy(level -> {
            assertThat(level.feeCents()).isZero();
            assertThat(level.interestBp()).isZero();
        });
    }

    @Test
    void suggestsTheFirstLevelOnceTheThresholdIsReachedAndStopsAfterPayment() {
        var onTime = invoice(10);
        var young = invoice(15);
        var old = invoice(30);

        // overdue 1 day, 16 days: only the older invoice reaches the 7 day threshold of the first level
        assertThat(dunnings.suggestions()).extracting(DunningSuggestion::invoiceId).containsExactly(old.id());
        assertThat(dunnings.suggestions().getFirst().level().level()).isEqualTo(1);
        assertThat(dunnings.suggestions().getFirst().amounts().openCents()).isEqualTo(100_000);
        assertThat(onTime.id()).isNotNull();
        assertThat(young.id()).isNotNull();

        payments.record(old.id(), new PaymentInput(TODAY, 100_000, PaymentMethod.BANK_TRANSFER, null, null));

        assertThat(dunnings.suggestions()).isEmpty();
    }

    @Test
    void partialPaymentReducesTheOpenAmountOfTheSuggestion() {
        var old = invoice(30);
        payments.record(old.id(), new PaymentInput(TODAY, 30_000, PaymentMethod.BANK_TRANSFER, null, null));

        assertThat(dunnings.suggestions().getFirst().amounts().openCents()).isEqualTo(70_000);
    }

    @Test
    void cancelledInvoicesAreNotDunned() {
        var old = invoice(30);

        finalizer.cancelInvoice(old.id());

        assertThat(dunnings.suggestions()).isEmpty();
        assertThatThrownBy(() -> dunnings.create(old.id(), levelId(1)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("dunning.error.notDue");
    }

    @Test
    void creatingAssignsTheNextNumberArchivesThePdfAndLeavesTheInvoiceUntouched() throws Exception {
        var old = invoice(30);
        var before = jdbc.sql("SELECT status, updated_at, gross_total_cents, number FROM document WHERE id = :id")
                .param("id", old.id()).query().singleRow();

        var dunning = dunnings.create(old.id(), levelId(1));

        assertThat(dunning.number()).isEqualTo("MA" + TODAY.getYear() + "00001");
        assertThat(dunning.level()).isEqualTo(1);
        assertThat(dunning.openCents()).isEqualTo(100_000);
        assertThat(dunning.newDueDate()).isEqualTo(TODAY.plusDays(7));

        var files = archive.findByOwner(ArchiveOwnerType.DUNNING, dunning.id());
        assertThat(files).hasSize(1);
        assertThat(files.getFirst().path()).isEqualTo("dunning/" + TODAY.getYear() + "/" + dunning.number() + ".pdf");

        var content = Files.readAllBytes(Path.of("target/test-archive").resolve(files.getFirst().path()));
        assertThat(files.getFirst().sha256())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));

        try (var pdf = Loader.loadPDF(content)) {
            assertThat(new PDFTextStripper().getText(pdf)).contains(dunning.number(), old.number(), "Muster GmbH",
                    "1.000,00", "Zahlungserinnerung");
        }

        assertThat(audit.actionsOf(AuditObjectType.DUNNING, dunning.id())).containsExactly(AuditAction.DUNNING_CREATE);
        assertThat(dunnings.findPdf(dunning.id())).isPresent();
        assertThat(jdbc.sql("SELECT status, updated_at, gross_total_cents, number FROM document WHERE id = :id")
                .param("id", old.id()).query().singleRow()).isEqualTo(before);
        assertThat(dunnings.suggestions()).isEmpty();
    }

    @Test
    void numbersAreGapFreeAndALevelIsIssuedOnlyOnce() {
        var first = invoice(30);
        var second = invoice(31);

        var one = dunnings.create(first.id(), levelId(1));
        var two = dunnings.create(second.id(), levelId(1));

        assertThat(two.number()).isEqualTo("MA" + TODAY.getYear() + "00002");
        assertThat(one.number()).endsWith("00001");
        assertThatThrownBy(() -> dunnings.create(first.id(), levelId(1)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("dunning.error.issued");
    }

    @Test
    void failingRenderRollsBackAndTheNumberStaysFree() {
        var old = invoice(30);
        doThrow(new IllegalStateException("render failed")).when(renderer).renderPdf(any(DunningRenderModel.class));

        assertThatThrownBy(() -> dunnings.create(old.id(), levelId(1))).isInstanceOf(IllegalStateException.class);

        assertThat(dunnings.listFor(old.id())).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM file WHERE owner_type = 'DUNNING'").query(Long.class).single()).isZero();

        reset(renderer);

        assertThat(dunnings.create(old.id(), levelId(1)).number()).endsWith("00001");
    }

    @Test
    void previewStoresNothingAndTakesNoNumber() {
        var old = invoice(30);

        var pdf = dunnings.preview(old.id(), levelId(1));

        assertThat(pdf).isNotEmpty();
        assertThat(dunnings.listFor(old.id())).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM number_sequence WHERE scope = 'DUNNING'").query(Long.class).single())
                .isZero();
    }

    @Test
    void feeAndInterestOfTheLevelAreAppliedWhenSwitchedOn() {
        var old = invoice(30);
        var level = levels.list().getFirst();

        levels.update(level.id(), new DunningLevelInput(level.level(), level.name(), level.daysAfterDue(), 500, 1000,
                level.newDueDays(), level.headerText(), level.footerText(), true));

        var dunning = dunnings.create(old.id(), level.id());

        // 100,000 cents at 10 % for the 16 days since the due date: 438.36 cents
        assertThat(dunning.feeCents()).isEqualTo(500);
        assertThat(dunning.interestCents()).isEqualTo(438);
        assertThat(dunning.totalCents()).isEqualTo(100_938);
    }

    @Test
    void nextLevelIsSuggestedOnlyAfterTheTermOfTheLastLetter() {
        var old = invoice(60);

        dunnings.create(old.id(), levelId(1));

        assertThat(dunnings.suggestions()).isEmpty();

        jdbc.sql("UPDATE dunning SET new_due_date = :date").param("date", TODAY.minusDays(1).toString()).update();

        assertThat(dunnings.suggestions()).extracting(suggestion -> suggestion.level().level()).containsExactly(2);
    }

    @Test
    void levelNumberCannotChangeOnceLettersExist() {
        var old = invoice(30);
        var level = levels.list().getFirst();

        dunnings.create(old.id(), level.id());

        assertThatThrownBy(() -> levels.update(level.id(), new DunningLevelInput(9, level.name(), level.daysAfterDue(),
                0, 0, level.newDueDays(), level.headerText(), level.footerText(), true)))
                .hasMessage("dunning.level.error.inUse");
    }

    @Test
    void inactiveLevelCannotBeCreated() {
        var old = invoice(30);
        var level = levels.list().getFirst();

        levels.update(level.id(), new DunningLevelInput(level.level(), level.name(), level.daysAfterDue(), 0, 0,
                level.newDueDays(), level.headerText(), level.footerText(), false));

        assertThatThrownBy(() -> dunnings.create(old.id(), level.id()))
                .hasMessage("dunning.error.inactive");
        assertThat(dunnings.suggestions()).isEmpty();
    }
}
