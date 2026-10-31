package de.cgoit.faktura.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.shared.MailTone;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {

    @Test
    void snapshotStoredBeforeTheEInvoiceModeExistedCountsAsNone() {
        var json = "{\"number\":\"KD0001\",\"name\":\"Muster GmbH\",\"country\":\"DE\"}";

        var snapshot = new SnapshotCodec().recipient(json);

        assertThat(snapshot.einvoiceMode()).isEqualTo(EInvoiceMode.NONE);
    }

    @Test
    void snapshotStoredBeforeMailRecipientsAndToneExistedHasNeitherAndIgnoresUnknownFields() {
        var json = "{\"number\":\"KD0001\",\"name\":\"Muster GmbH\",\"country\":\"DE\","
                + "\"invoiceEmail\":\"rechnung@example.test\",\"contactEmail\":\"kontakt@example.test\","
                + "\"einvoiceMode\":\"ZUGFERD\",\"somethingNew\":1}";

        var snapshot = new SnapshotCodec().recipient(json);

        assertThat(snapshot.mailRecipients()).isNull();
        assertThat(snapshot.mailTone()).isNull();
        assertThat(snapshot.invoiceEmail()).isEqualTo("rechnung@example.test");
        assertThat(snapshot.contactEmail()).isEqualTo("kontakt@example.test");
        assertThat(snapshot.einvoiceMode()).isEqualTo(EInvoiceMode.ZUGFERD);
    }

    @Test
    void mailRecipientsAndToneSurviveWritingAndReading() {
        var codec = new SnapshotCodec();
        var recipient = new RecipientSnapshot("KD0001", "Muster GmbH", null, null, null, "DE", null, null, null, null,
                null, EInvoiceMode.NONE, null, null, "a@example.test, b@example.test", MailTone.INFORMAL);

        var read = codec.recipient(codec.write(null, recipient, null).recipient());

        assertThat(read.mailRecipients()).isEqualTo("a@example.test, b@example.test");
        assertThat(read.mailTone()).isEqualTo(MailTone.INFORMAL);
    }
}
