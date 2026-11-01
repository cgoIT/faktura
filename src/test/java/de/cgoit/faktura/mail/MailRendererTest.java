package de.cgoit.faktura.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import de.cgoit.faktura.mail.internal.MailRenderer;
import de.cgoit.faktura.textblock.Placeholder;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mail.javamail.MimeMessageHelper;

class MailRendererTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @TempDir
    Path directory;

    private MailRenderer renderer(String logoPath) {
        return new MailRenderer(logoPath);
    }

    private String logoFile() throws Exception {
        var file = Files.write(directory.resolve("logo.png"), PNG);

        return file.toString();
    }

    @Test
    void linksAreClickableInHtmlAndPrintTheAddressInText() {
        var renderer = renderer("");
        var body = "Mehr unter [unserer Website](https://example.org/a_b).";

        assertThat(renderer.mailHtml("Betreff", body))
                .contains("<a href=\"https://example.org/a_b\">unserer Website</a>");
        assertThat(renderer.text(body)).isEqualTo("Mehr unter unserer Website (https://example.org/a_b).");
    }

    @Test
    void logoStandsWhereThePlaceholderWas() throws Exception {
        var renderer = renderer(logoFile());
        var body = "Hallo\n\n" + Placeholder.LOGO_MARKER + "\n\nTschüss";

        assertThat(renderer.html("Betreff", body))
                .contains("<p>Hallo</p><p><img src=\"data:image/png;base64,")
                .contains("<p>Tschüss</p>");
        assertThat(renderer.mailHtml("Betreff", body)).contains("<p><img src=\"cid:logo\"");
        assertThat(renderer.text(body)).isEqualTo("Hallo\n\nTschüss");
    }

    @Test
    void noPlaceholderNoLogo() throws Exception {
        assertThat(renderer(logoFile()).mailHtml("Betreff", "Text")).doesNotContain("<img");
    }

    @Test
    void withoutLogoFileThePlaceholderVanishes() {
        var body = "A " + Placeholder.LOGO_MARKER + " B";

        assertThat(renderer("").mailHtml("Betreff", body)).contains("<p>A  B</p>").doesNotContain("<img");
        assertThat(renderer(directory.resolve("missing.png").toString()).mailHtml("Betreff", body))
                .doesNotContain("<img");
    }

    @Test
    void sentMailCarriesTheLogoAsInlineImageOnlyWhenTheTextUsesIt() throws Exception {
        var renderer = renderer(logoFile());

        assertThat(raw(renderer, Placeholder.LOGO_MARKER + "\n\nText"))
                .contains("Content-ID: <logo>", "image/png", "text/plain", "text/html");
        assertThat(raw(renderer, "Text")).doesNotContain("image/png");
    }

    private String raw(MailRenderer renderer, String body) throws Exception {
        var message = new MimeMessage((Session) null);
        var helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED, "UTF-8");

        helper.setText(renderer.text(body), renderer.mailHtml("Betreff", body));
        renderer.attachLogo(helper, body);
        message.saveChanges();

        var raw = new ByteArrayOutputStream();

        message.writeTo(raw);

        return raw.toString();
    }
}
