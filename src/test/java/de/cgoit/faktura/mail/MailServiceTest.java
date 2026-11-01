package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import de.cgoit.faktura.SqliteTest;
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
import de.cgoit.faktura.document.OfferEvent;
import de.cgoit.faktura.document.OfferEventType;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.OfferState;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.dunning.DunningLevelService;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.shared.MailTone;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailSendException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class MailServiceTest {

    private static final LocalDate TODAY = LocalDate.now();

    @Autowired
    private MailService mail;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private OfferService offers;

    @Autowired
    private DunningService dunnings;

    @Autowired
    private DunningLevelService levels;

    @Autowired
    private CustomerService customers;

    @Autowired
    private MailTemplateService templates;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private AuditService audit;

    @Autowired
    private CapturingMailSender sender;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    private String customerId;
    private String contactId;

    @BeforeEach
    void setUp() {
        MailTestData.cleanUp(jdbc);
        sender.reset();
        MailTestData.company(jdbc);

        var ids = MailTestData.customer(customers);

        customerId = ids[0];
        contactId = ids[1];
    }

    @AfterEach
    void cleanUp() {
        MailTestData.cleanUp(jdbc);
        sender.reset();
    }

    private InvoiceInfo invoice() {
        return MailTestData.invoice(invoices, finalizer, customerId, contactId, TODAY.minusDays(30));
    }

    private static MailTarget document(InvoiceInfo invoice) {
        return new MailTarget(MailOwnerType.DOCUMENT, invoice.id());
    }

    private SendMailCommand command(MailTarget target) {
        var draft = mail.prepare(target, null);

        return new SendMailCommand(target, draft.recipients(), draft.subject(), draft.body(), true);
    }

    private InvoiceInfo offer() {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createOfferDraft(new InvoiceInput(customerId, TODAY, null, null, null, "Betreff", "Kopf",
                "Fuß", TotalDiscount.none(), List.of(item), contactId));

        return finalizer.finalizeInvoice(draft.id());
    }

    @Test
    void sendingAnOfferUsesTheOfferTemplateAndRecordsTheSentEvent() throws Exception {
        var offer = offer();
        var target = document(offer);
        var draft = mail.prepare(target, null);

        assertThat(draft.scope()).isEqualTo(MailScope.OFFER);
        assertThat(draft.subject()).contains(offer.number());
        assertThat(draft.body()).contains(offer.number()).contains(TODAY.plusDays(30).format(
                DateTimeFormatter.ofPattern("dd.MM.yyyy")));

        mail.send(command(target));

        var attachment = ((Multipart) sender.sent().getFirst().getContent()).getBodyPart(1);

        assertThat(attachment.getFileName()).isEqualTo("Angebot " + offer.number() + ".pdf");
        assertThat(offers.events(offer.id())).extracting(OfferEvent::type).containsExactly(OfferEventType.SENT);
        assertThat(offers.state(offer.id())).contains(OfferState.SENT);
    }

    @Test
    void aFailedOfferMailRecordsNoSentEvent() {
        var offer = offer();
        var command = command(document(offer));

        sender.failWith(new MailSendException("550 rejected"));

        assertThatThrownBy(() -> mail.send(command)).isInstanceOf(RuleViolationException.class);
        assertThat(offers.events(offer.id())).isEmpty();
        assertThat(offers.state(offer.id())).contains(OfferState.OPEN);
    }

    @Test
    void defaultTemplatesExistInTwoVariantsPerDocumentType() {
        var templates = jdbc.sql("SELECT scope, name, is_default FROM mail_template ORDER BY scope, name")
                .query()
                .listOfRows();

        assertThat(templates).hasSize(9);
        assertThat(templates).extracting(row -> row.get("scope")).containsExactly("CANCELLATION", "CANCELLATION",
                "DUNNING", "DUNNING", "INVOICE", "INVOICE", "NOTIFICATION_DIGEST", "OFFER", "OFFER");
        assertThat(templates.stream().filter(row -> ((Number) row.get("is_default")).intValue() == 1)).hasSize(5);
    }

    @Test
    void prepareFillsRecipientSubjectTextAndAttachmentFromTheDocument() {
        var invoice = invoice();

        var draft = mail.prepare(document(invoice), null);

        assertThat(draft.scope()).isEqualTo(MailScope.INVOICE);
        assertThat(draft.recipients()).isEqualTo(MailTestData.CONTACT_EMAIL);
        assertThat(draft.template()).isPresent();
        assertThat(draft.subject()).contains(invoice.number()).contains("Beispiel Nebengewerbe");
        assertThat(draft.body()).contains("Sehr geehrte Frau Beispiel").contains(invoice.number())
                .contains("1.000,00").doesNotContain("{");
        assertThat(draft.attachmentName()).isEqualTo("Rechnung " + invoice.number() + ".pdf");
        assertThat(draft.attachmentBytes()).isPositive();
        assertThat(draft.blockedKey()).isNull();
        assertThat(draft.lastSent()).isEmpty();
    }

    @Test
    void prepareWithAnotherTemplateStartsFromThatText() {
        var invoice = invoice();
        var informal = jdbc.sql("SELECT id FROM mail_template WHERE scope = 'INVOICE' AND name = 'Informell'")
                .query(String.class)
                .single();

        var draft = mail.prepare(document(invoice), informal);

        assertThat(draft.template().orElseThrow().name()).isEqualTo("Informell");
        assertThat(draft.body()).startsWith("Hallo Erika,");
    }

    @Test
    void firstAndLastNameOfTheContactAreSeparatePlaceholdersAndEmptyWithoutContact() {
        var withContact = invoice();
        var without = MailTestData.invoice(invoices, finalizer, customerId, null, TODAY);
        var preview = mail.previewTemplate("{ansprechpartner_vorname}|{ansprechpartner_nachname}",
                "Hallo {ansprechpartner_vorname} {ansprechpartner_nachname}", document(withContact));
        var empty = mail.previewTemplate("{ansprechpartner_vorname}|{ansprechpartner_nachname}", "Hallo", document(without));

        assertThat(preview.subject()).isEqualTo("Erika|Beispiel");
        assertThat(preview.text()).isEqualTo("Hallo Erika Beispiel");
        assertThat(empty.subject()).isEqualTo("|");
    }

    @Test
    void recipientFallsBackToTheInvoiceAddressWithoutContact() {
        var invoice = MailTestData.invoice(invoices, finalizer, customerId, null, TODAY);

        assertThat(mail.prepare(document(invoice), null).recipients()).isEqualTo(MailTestData.CUSTOMER_EMAIL);
    }

    @Test
    void severalTemplatesWithTheSameToneYieldTheFirstByName() {
        var customer = customers.find(customerId).orElseThrow();

        templates.create(new MailTemplateInput(MailScope.INVOICE, "Alternativ informell", "Hi", "Hallo", false,
                MailTone.INFORMAL));
        customers.update(customerId, new CustomerInput(customer.name(), customer.street(), customer.zip(),
                customer.city(), customer.country(), customer.vatId(), customer.invoiceEmail(),
                customer.paymentDays(), customer.discountBp(), customer.note(), customer.einvoiceMode(), null,
                MailTone.INFORMAL));

        assertThat(mail.prepare(document(invoice()), null).template().orElseThrow().name())
                .isEqualTo("Alternativ informell");
    }

    @Test
    void customerWithoutToneGetsTheDefaultTemplate() {
        var draft = mail.prepare(document(invoice()), null);

        assertThat(draft.template().orElseThrow().isDefault()).isTrue();
    }

    @Test
    void sendingToMoreAddressesThanAllowedIsRefused() {
        var invoice = invoice();
        var target = document(invoice);
        var prepared = mail.prepare(target, null);
        var many = "a@example.test, b@example.test, c@example.test, d@example.test, e@example.test, f@example.test";

        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, many, prepared.subject(), prepared.body(),
                true))).hasMessage("mail.error.recipientCount");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void customerMailRecipientsWinOverContactAndInvoiceAddress() {
        var customer = customers.find(customerId).orElseThrow();

        customers.update(customerId, new CustomerInput(customer.name(), customer.street(), customer.zip(),
                customer.city(), customer.country(), customer.vatId(), customer.invoiceEmail(),
                customer.paymentDays(), customer.discountBp(), customer.note(), customer.einvoiceMode(),
                "a@example.test; b@example.test"));

        var invoice = invoice();

        assertThat(mail.prepare(document(invoice), null).recipients()).isEqualTo("a@example.test, b@example.test");
    }

    @Test
    void sendsMultipartMessageWithTheArchivedPdfAsUnchangedAttachment() throws Exception {
        var invoice = invoice();
        var target = document(invoice);
        var command = command(target);

        var entry = mail.send(command);

        assertThat(sender.sent()).hasSize(1);

        var message = sender.sent().getFirst();
        var root = (Multipart) message.getContent();

        assertThat(message.isMimeType("multipart/mixed")).isTrue();
        assertThat(root.getCount()).isEqualTo(2);

        var related = root.getBodyPart(0);

        assertThat(related.isMimeType("multipart/related")).isTrue();

        var alternative = ((Multipart) related.getContent()).getBodyPart(0);

        assertThat(alternative.isMimeType("multipart/alternative")).isTrue();
        assertThat(((Multipart) alternative.getContent()).getBodyPart(0).isMimeType("text/plain")).isTrue();
        assertThat(((Multipart) alternative.getContent()).getBodyPart(1).isMimeType("text/html")).isTrue();

        var attachment = root.getBodyPart(1);

        assertThat(attachment.getFileName()).isEqualTo("Rechnung " + invoice.number() + ".pdf");
        assertThat(attachment.getDisposition()).isEqualTo(Part.ATTACHMENT);
        assertThat(attachment.isMimeType("application/pdf")).isTrue();

        var file = archive.findByOwner(ArchiveOwnerType.DOCUMENT, invoice.id()).stream()
                .filter(found -> found.type().name().equals("PDF"))
                .findFirst()
                .orElseThrow();
        var bytes = attachment.getInputStream().readAllBytes();

        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
                .isEqualTo(file.sha256());
        assertThat(bytes).isEqualTo(Files.readAllBytes(Path.of("target/test-archive").resolve(file.path())));

        assertThat(message.getSubject()).isEqualTo(command.subject());
        assertThat(message.getFrom()[0].toString()).contains("rechnung@example.test").contains("Beispiel Nebengewerbe");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo(MailTestData.CONTACT_EMAIL);
        assertThat(entry.sent()).isTrue();
        assertThat(entry.attachmentName()).isEqualTo("Rechnung " + invoice.number() + ".pdf");
    }

    @Test
    void sendingIsLoggedAndAuditedAndShownInTheHistory() {
        var invoice = invoice();
        var target = document(invoice);

        mail.send(command(target));

        var row = jdbc.sql("SELECT * FROM mail_log").query().singleRow();

        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("recipients")).isEqualTo(MailTestData.CONTACT_EMAIL);
        assertThat(row.get("message_id")).isNotNull();
        assertThat(row.get("attachment_file_id")).isNotNull();
        assertThat(row.get("error_key")).isNull();
        assertThat(audit.actionsOf(AuditObjectType.DOCUMENT, invoice.id())).contains(AuditAction.MAIL_SEND);
        assertThat(mail.history(target)).hasSize(1);
        assertThat(mail.prepare(target, null).lastSent()).isPresent();
    }

    @Test
    void cancellationCanBeSentWithItsOwnTemplateAndAttachmentName() throws Exception {
        var invoice = invoice();
        var cancellation = finalizer.cancelInvoice(invoice.id());
        var target = document(cancellation);

        var draft = mail.prepare(target, null);

        assertThat(draft.scope()).isEqualTo(MailScope.CANCELLATION);
        assertThat(draft.attachmentName()).isEqualTo("Stornorechnung " + cancellation.number() + ".pdf");

        mail.send(command(target));

        var root = (Multipart) sender.sent().getFirst().getContent();

        assertThat(root.getBodyPart(1).getFileName()).isEqualTo("Stornorechnung " + cancellation.number() + ".pdf");
    }

    @Test
    void dunningLetterCanBeSentWithItsValues() throws Exception {
        var invoice = invoice();
        var dunning = dunnings.create(invoice.id(), levels.list().getFirst().id());
        var target = new MailTarget(MailOwnerType.DUNNING, dunning.id());

        var draft = mail.prepare(target, null);

        assertThat(draft.scope()).isEqualTo(MailScope.DUNNING);
        assertThat(draft.attachmentName()).isEqualTo("Mahnung " + dunning.number() + ".pdf");
        assertThat(draft.recipients()).isEqualTo(MailTestData.CONTACT_EMAIL);
        assertThat(draft.subject()).contains("Zahlungserinnerung").contains(invoice.number());
        assertThat(draft.body()).contains("1.000,00").doesNotContain("{");

        mail.send(command(target));

        assertThat(sender.sent()).hasSize(1);
        assertThat(audit.actionsOf(AuditObjectType.DUNNING, dunning.id())).contains(AuditAction.MAIL_SEND);
        assertThat(((Multipart) sender.sent().getFirst().getContent()).getBodyPart(1).getFileName())
                .isEqualTo("Mahnung " + dunning.number() + ".pdf");
    }

    @Test
    void draftsCannotBeSent() {
        var item = new InvoiceItemInput("Beratung", 10_000, "Std", 10_000, 0, "E", 0, null);
        var draft = invoices.createDraft(new InvoiceInput(customerId, TODAY, null, null, 14, "Betreff", "Kopf", "Fuß",
                TotalDiscount.none(), List.of(item)));
        var target = new MailTarget(MailOwnerType.DOCUMENT, draft.id());

        assertThatThrownBy(() -> mail.prepare(target, null)).isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.notFinal");
        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, "a@example.test", "Betreff", "Text", true)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.notFinal");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void nothingIsSentWithoutConfirmation() {
        var target = document(invoice());
        var draft = mail.prepare(target, null);

        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, draft.recipients(), draft.subject(),
                draft.body(), false)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.notConfirmed");
        assertThat(sender.sent()).isEmpty();
        assertThat(jdbc.sql("SELECT COUNT(*) FROM mail_log").query(Integer.class).single()).isZero();
    }

    @Test
    void invalidAddressesAndSubjectsAreRejected() {
        var target = document(invoice());

        for (var recipients : new String[] {"kein-mail", "a@example.test, kaputt", "Max <max@example.test>",
                "a@example.test,b@example.test,c@example.test,d@example.test,e@example.test,f@example.test"}) {
            assertThatThrownBy(() -> mail.send(new SendMailCommand(target, recipients, "Betreff", "Text", true)))
                    .isInstanceOf(RuleViolationException.class)
                    .hasMessageStartingWith("mail.error.recipient");
        }

        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, "a@example.test", "Betreff\nBcc: x@example.test",
                "Text", true)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.subjectLine");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void semicolonAndDuplicatesAreTolerated() throws Exception {
        var target = document(invoice());

        mail.send(new SendMailCommand(target, "a@example.test; b@example.test, A@example.test", "Betreff", "Text", true));

        assertThat(sender.sent().getFirst().getAllRecipients()).hasSize(2);
    }

    @Test
    void failedSendIsLoggedAsFailedWithoutPersonalDataAndReported() {
        var invoice = invoice();
        var target = document(invoice);
        var command = command(target);

        sender.failWith(new MailSendException("550 rejected kunde@example.test"));

        assertThatThrownBy(() -> mail.send(command)).isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.smtp")
                .hasMessageNotContaining("example.test");

        var row = jdbc.sql("SELECT * FROM mail_log").query().singleRow();

        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("error_key")).isEqualTo("mail.error.smtp");
        assertThat(row.get("message_id")).isNull();
        assertThat(mail.history(target)).singleElement().satisfies(entry -> assertThat(entry.sent()).isFalse());
        assertThat(mail.prepare(target, null).lastSent()).isEmpty();
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void connectionProblemsGetTheirOwnMessage() {
        var command = command(document(invoice()));

        sender.failWith(new MailSendException("failed", new ConnectException("refused")));

        assertThatThrownBy(() -> mail.send(command)).hasMessage("mail.error.connect");
    }

    @Test
    void htmlPartEscapesUserContentAndTheTextPartMatchesIt() throws Exception {
        var target = document(invoice());

        mail.send(new SendMailCommand(target, "a@example.test", "Grüße <b>fett</b>",
                "Hallo <script>alert(1)</script> & Äpfel\n\n**wichtig**\n\n- eins\n- zwei", true));

        var message = sender.sent().getFirst();
        var html = (String) MailTestData.find(message, "text/html").getContent();
        var text = (String) MailTestData.find(message, "text/plain").getContent();

        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("&amp;").contains("Äpfel")
                .contains("<b>wichtig</b>").contains("<li>eins</li>").doesNotContain("<script>")
                .doesNotContain("http://").doesNotContain("https://").doesNotContain("<img");
        assertThat(text).contains("<script>alert(1)</script> & Äpfel").contains("wichtig").doesNotContain("**")
                .contains("- eins");
        assertThat(message.getSubject()).isEqualTo("Grüße <b>fett</b>");
    }

    @Test
    void sendingNeverHoldsTheSingleDatabaseConnectionNorATransaction() {
        var command = command(document(invoice()));
        var activeConnections = new AtomicInteger(-1);
        var inTransaction = new AtomicReference<Boolean>();

        sender.beforeSend(message -> {
            activeConnections.set(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections());
            inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
        });

        mail.send(command);

        assertThat(activeConnections.get()).isZero();
        assertThat(inTransaction.get()).isFalse();
    }

    @Test
    void sendingInsideATransactionIsRefused() {
        var command = command(document(invoice()));

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> mail.send(command)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void largeAttachmentsAreBlockedAndSayWhy() {
        var invoice = invoice();

        jdbc.sql("UPDATE file SET size_bytes = 99999999 WHERE owner_id = :id AND type = 'PDF'")
                .param("id", invoice.id())
                .update();

        var target = document(invoice);

        assertThat(mail.prepare(target, null).blockedKey()).isEqualTo("mail.error.tooLarge");
        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, "a@example.test", "Betreff", "Text", true)))
                .hasMessage("mail.error.tooLarge");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void tamperedArchiveFileIsNotSent() throws Exception {
        var invoice = invoice();
        var file = archive.findByOwner(ArchiveOwnerType.DOCUMENT, invoice.id()).getFirst();
        var command = command(document(invoice));

        Files.write(Path.of("target/test-archive").resolve(file.path()), new byte[] {1, 2, 3});

        assertThatThrownBy(() -> mail.send(command)).hasMessage("mail.error.attachmentIntegrity");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void noticeGoesToOneRecipientWithoutAttachmentOrBlindCopy() throws Exception {
        mail.sendNotice("betreiber@example.test", "Hinweise", "Hallo **Welt**");

        var message = sender.sent().getFirst();

        assertThat(message.getAllRecipients()).hasSize(1);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("betreiber@example.test");
        assertThat(message.getSubject()).isEqualTo("Hinweise");
        assertThat((String) MailTestData.find(message, "text/plain").getContent()).isEqualTo("Hallo Welt");
        assertThat(MailTestData.find(message, "application/pdf")).isNull();
    }

    @Test
    void noticeRejectsSeveralOrInvalidRecipients() {
        assertThatThrownBy(() -> mail.sendNotice("a@example.test, b@example.test", "S", "T"))
                .hasMessage("mail.error.recipientCount");
        assertThatThrownBy(() -> mail.sendNotice("kein-mail", "S", "T")).hasMessage("mail.error.recipients");
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void noticeFailureIsReportedAsKeyWithoutTheMessageText() {
        sender.failWith(new MailSendException("boom betreiber@example.test"));

        assertThatThrownBy(() -> mail.sendNotice("betreiber@example.test", "S", "T")).hasMessage("mail.error.smtp");
    }

    @Test
    void noticeIsRefusedInsideATransaction() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                mail.sendNotice("betreiber@example.test", "S", "T"))).isInstanceOf(IllegalStateException.class);
        assertThat(sender.sent()).isEmpty();
    }

    @Test
    void previewOfAMessageRendersBothParts() {
        var preview = mail.preview("Betreff *x*", "Hallo **Welt**");

        assertThat(preview.subject()).isEqualTo("Betreff *x*");
        assertThat(preview.html()).contains("<b>Welt</b>");
        assertThat(preview.text()).isEqualTo("Hallo Welt");
    }
}
