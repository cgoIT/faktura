package de.cgoit.faktura.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import de.cgoit.faktura.render.RenderModel.Account;
import org.junit.jupiter.api.Test;

class GirocodeServiceTest {

    private final GirocodeService girocode = new GirocodeService();
    private final Account account = new Account("Erika Beispiel", "Testbank", "DE02 1203 0000 0000 2020 51",
            "BYLADEM1001");

    @Test
    void payloadFollowsTheEpcLayout() {
        var lines = girocode.payload(account, 10105, "RE202700001").split("\n", -1);

        assertThat(lines).containsExactly("BCD", "002", "1", "SCT", "BYLADEM1001", "Erika Beispiel",
                "DE02120300000000202051", "EUR101.05", "", "", "RE202700001");
    }

    @Test
    void amountsBelowOneEuroKeepTheLeadingZero() {
        assertThat(girocode.payload(account, 5, "x")).contains("EUR0.05");
    }

    @Test
    void longNamesAndRemittanceAreCutToTheEpcLimits() {
        var longAccount = new Account("N".repeat(100), null, "DE02120300000000202051", null);
        var lines = girocode.payload(longAccount, 100, "R".repeat(200)).split("\n", -1);

        assertThat(lines[4]).isEmpty();
        assertThat(lines[5]).hasSize(70);
        assertThat(lines[10]).hasSize(140);
    }

    @Test
    void placeholderIsAnOpaqueSquarePngDifferentFromARealCode() throws IOException {
        var placeholder = girocode.placeholderPng();
        var image = ImageIO.read(new ByteArrayInputStream(placeholder));

        assertThat(image.getWidth()).isEqualTo(image.getHeight());
        assertThat(image.getColorModel().hasAlpha()).isFalse();
        assertThat(placeholder).isNotEqualTo(girocode.png(account, 1000, "RE1"));
    }

    @Test
    void imageIsAnOpaqueSquarePng() throws IOException {
        var image = ImageIO.read(new ByteArrayInputStream(girocode.png(account, 1000, "RE1")));

        assertThat(image.getWidth()).isEqualTo(image.getHeight());
        assertThat(image.getColorModel().hasAlpha()).isFalse();
    }
}
