package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvoiceItemInfoTest {

    @Test
    void unitLabelIsSingularForExactlyOneAndPluralOtherwise() {
        assertThat(item(1000, "Tage").unitLabel()).isEqualTo("Tag");
        assertThat(item(2000, "Tage").unitLabel()).isEqualTo("Tage");
        assertThat(item(1500, "Tage").unitLabel()).isEqualTo("Tage");
        assertThat(item(0, "Tage").unitLabel()).isEqualTo("Tage");
    }

    @Test
    void unitLabelFallsBackToTheNameWithoutAPlural() {
        assertThat(item(2000, null).unitLabel()).isEqualTo("Tag");
        assertThat(item(2000, " ").unitLabel()).isEqualTo("Tag");
    }

    private static InvoiceItemInfo item(long quantityThousandths, String plural) {
        return new InvoiceItemInfo(null, 1, "Beratung", quantityThousandths, "Tag", "Tag", plural, "DAY", 60000, 0,
                "E", 0, 0, null);
    }
}
