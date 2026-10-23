package de.cgoit.faktura.document.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.cgoit.faktura.customer.EInvoiceMode;
import org.junit.jupiter.api.Test;

class SnapshotCodecTest {

    @Test
    void snapshotStoredBeforeTheEInvoiceModeExistedCountsAsNone() {
        var json = "{\"number\":\"KD0001\",\"name\":\"Muster GmbH\",\"country\":\"DE\"}";

        var snapshot = new SnapshotCodec().recipient(json);

        assertThat(snapshot.einvoiceMode()).isEqualTo(EInvoiceMode.NONE);
    }
}
