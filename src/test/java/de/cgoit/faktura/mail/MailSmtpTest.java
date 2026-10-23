package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import de.cgoit.faktura.archive.ArchiveOwnerType;
import de.cgoit.faktura.archive.ArchiveService;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.mail.Multipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sends through the real {@code JavaMailSender} to an embedded SMTP server and checks what arrives.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:file:faktura-test?mode=memory&cache=shared",
        "faktura.archive.path=target/test-archive", "faktura.backup.path=target/test-backup",
        "faktura.recurring.enabled=false", "faktura.mail.enabled=true", "faktura.mail.bcc=archiv@example.test",
        "faktura.mail.reply-to=antwort@example.test", "spring.mail.host=localhost", "spring.mail.port=3025",
        "spring.mail.properties.mail.smtp.auth=false", "spring.mail.properties.mail.smtp.starttls.enable=false",
        "spring.mail.properties.mail.smtp.starttls.required=false" })
class MailSmtpTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private MailService mail;

    @Autowired
    private InvoiceService invoices;

    @Autowired
    private FinalizeService finalizer;

    @Autowired
    private CustomerService customers;

    @Autowired
    private ArchiveService archive;

    @Autowired
    private JdbcClient jdbc;

    private InvoiceInfo invoice;

    @BeforeEach
    void setUp() {
        MailTestData.cleanUp(jdbc);
        MailTestData.company(jdbc);

        var ids = MailTestData.customer(customers);

        invoice = MailTestData.invoice(invoices, finalizer, ids[0], ids[1], LocalDate.now());
    }

    @AfterEach
    void cleanUp() {
        MailTestData.cleanUp(jdbc);
    }

    @Test
    void mailArrivesWithBothPartsAnAttachmentAndTheBlindCopy() throws Exception {
        var target = new MailTarget(MailOwnerType.DOCUMENT, invoice.id());
        var draft = mail.prepare(target, null);

        mail.send(new SendMailCommand(target, draft.recipients(), draft.subject(), draft.body(), true));

        var received = greenMail.getReceivedMessages();

        assertThat(received).hasSize(2);

        var message = received[0];
        var root = (Multipart) message.getContent();
        var file = archive.findByOwner(ArchiveOwnerType.DOCUMENT, invoice.id()).stream()
                .filter(found -> found.type().name().equals("PDF"))
                .findFirst()
                .orElseThrow();

        assertThat(message.getSubject()).isEqualTo(draft.subject());
        assertThat(message.getReplyTo()[0].toString()).isEqualTo("antwort@example.test");
        assertThat(message.isMimeType("multipart/mixed")).isTrue();
        assertThat(MailTestData.find(message, "text/plain")).isNotNull();
        assertThat(MailTestData.find(message, "text/html")).isNotNull();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(root.getBodyPart(1).getInputStream().readAllBytes()))).isEqualTo(file.sha256());
        assertThat(jdbc.sql("SELECT status FROM mail_log").query(String.class).single()).isEqualTo("SENT");
    }

    @Test
    void unreachableServerIsReportedAndLoggedAsFailed() {
        var target = new MailTarget(MailOwnerType.DOCUMENT, invoice.id());
        var draft = mail.prepare(target, null);

        greenMail.stop();

        assertThatThrownBy(() -> mail.send(new SendMailCommand(target, draft.recipients(), draft.subject(),
                draft.body(), true)))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("mail.error.connect");
        assertThat(jdbc.sql("SELECT status FROM mail_log").query(String.class).single()).isEqualTo("FAILED");
    }
}
