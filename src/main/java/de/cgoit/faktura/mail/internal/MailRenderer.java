package de.cgoit.faktura.mail.internal;

import de.cgoit.faktura.render.RichText;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * Turns the text of a mail into its two parts: a simple HTML document and a plain text fallback built from the same
 * source. All user content is escaped by {@link RichText}; the HTML has inline CSS only and no images, links or
 * external resources.
 */
@Component
public class MailRenderer {

    private static final String STYLE = """
            body { font-family: Arial, Helvetica, sans-serif; font-size: 15px; line-height: 1.5; color: #222222; }
            p { margin: 0 0 1em 0; }
            ul, ol { margin: 0 0 1em 0; }
            """;

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
     * Returns the HTML part: a complete document with the text as paragraphs and lists.
     */
    public String html(String subject, String body) {
        return "<!DOCTYPE html>\n<html lang=\"de\"><head><meta charset=\"utf-8\"><title>"
                + HtmlUtils.htmlEscape(subject, "UTF-8") + "</title><style>" + STYLE + "</style></head><body>"
                + RichText.toXhtml(body) + "</body></html>";
    }

    /**
     * Returns the plain text part.
     */
    public String text(String body) {
        return RichText.toPlainText(body);
    }
}
