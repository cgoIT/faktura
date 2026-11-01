package de.cgoit.faktura.mail.internal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.textblock.Placeholder;
import jakarta.mail.MessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Turns the text of a mail into its two parts: a simple HTML document and a plain text fallback built from the same
 * source. All user content is escaped by {@link RichText}; the HTML has one style element and no external resources.
 * Links are the ones the text contains. The logo of the PDFs ({@code faktura.render.logo-path}) is the only image; it
 * stands where the text has {@link Placeholder#LOGO_MARKER} (the value of the placeholder {@code {logo}}), travels
 * inside the mail (content id) and is never loaded from outside. No logo file, no image; the plain text part never has one.
 */
@Component
public class MailRenderer {

    private static final Logger LOG = LoggerFactory.getLogger(MailRenderer.class);

    private static final String LOGO_MARKER = Placeholder.LOGO_MARKER;
    private static final String LOGO_ID = "logo";

    private static final String STYLE = """
            body { font-family: Arial, Helvetica, sans-serif; font-size: 15px; line-height: 1.5; color: #222222; }
            p { margin: 0 0 1em 0; }
            ul, ol { margin: 0 0 1em 0; }
            """;

    private final String logoPath;

    public MailRenderer(@Value("${faktura.render.logo-path:}") String logoPath) {
        this.logoPath = logoPath;
    }

    /**
     * Returns the subject as plain text on one line: markup characters are resolved and line breaks removed.
     */
    public String subject(String text) {
        return RichText.toPlainText(text)
                .replaceAll("\\s*\\R\\s*", " ")
                .strip();
    }

    /**
     * Returns a subject the user has typed or confirmed as it stands: on one line, without markup processing.
     */
    public String finalSubject(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ")
                .strip();
    }

    /**
     * Returns the HTML part for the preview: a complete document with the text as paragraphs and lists, the logo as a
     * data URI.
     */
    public String html(String subject, String body) {
        return html(subject, body, logo()
                .map(logo -> "data:" + logo.contentType() + ";base64,"
                        + Base64.getEncoder().encodeToString(logo.bytes()))
                .orElse(null));
    }

    /**
     * Returns the HTML part of the mail that is sent: like the preview, but the logo is referenced by its content id;
     * {@link #attachLogo} adds the image.
     */
    public String mailHtml(String subject, String body) {
        return html(subject, body, logo().isPresent() ? "cid:" + LOGO_ID : null);
    }

    /**
     * Adds the logo as inline image to a message built with {@code MULTIPART_MODE_MIXED_RELATED}, if there is one and
     * the text uses it.
     */
    public void attachLogo(MimeMessageHelper helper, String body) throws MessagingException {
        var logo = logo();

        if (logo.isPresent() && body.contains(LOGO_MARKER)) {
            helper.addInline(LOGO_ID, new ByteArrayResource(logo.get().bytes()), logo.get().contentType());
        }
    }

    private String html(String subject, String body, String logoSource) {
        var logo = logoSource == null ? "" : "<img src=\"" + logoSource + "\" alt=\"Logo\" height=\"48\">";

        return "<!DOCTYPE html>\n<html lang=\"de\"><head><meta charset=\"utf-8\"><title>"
                + HtmlUtils.htmlEscape(subject, "UTF-8") + "</title><style>" + STYLE + "</style></head><body>"
                + RichText.toXhtml(body).replace(LOGO_MARKER, logo) + "</body></html>";
    }

    private Optional<Logo> logo() {
        if (logoPath == null || logoPath.isBlank()) {
            return Optional.empty();
        }

        var name = logoPath.toLowerCase(Locale.ROOT);
        var contentType = name.endsWith(".png") ? "image/png"
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? "image/jpeg"
                : name.endsWith(".gif") ? "image/gif"
                : null;

        if (contentType == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new Logo(Files.readAllBytes(Path.of(logoPath)), contentType));
        } catch (IOException e) {
            LOG.warn("The logo cannot be read, the mail goes without it ({})", e.getClass().getSimpleName());

            return Optional.empty();
        }
    }

    private record Logo(byte[] bytes, String contentType) {
    }

    /**
     * Returns the plain text part.
     */
    public String text(String body) {
        return RichText.toPlainText(body)
                .replace(LOGO_MARKER, "")
                .replaceAll("(?m)[ \\t]+$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
    }
}
