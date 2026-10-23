package de.cgoit.faktura.mail.internal;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.textblock.Placeholder;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Invented values for the placeholders, shown when a mail template is previewed without a document.
 * The values live in the message bundle ({@code mail.sample.<key>}). The sections of the notification mail carry
 * their own markup and are not escaped.
 */
@Component
public class SampleValues {

    private final MessageSource messages;

    public SampleValues(MessageSource messages) {
        this.messages = messages;
    }

    /**
     * Returns a value for every placeholder, escaped like the values of a real document.
     */
    public Map<Placeholder, String> values() {
        var values = new EnumMap<Placeholder, String>(Placeholder.class);

        for (var placeholder : Placeholder.values()) {
            var sample = messages.getMessage("mail.sample." + placeholder.key(), null, "", Locale.GERMAN);

            values.put(placeholder, placeholder.digestOnly() ? sample : RichText.escape(sample));
        }

        return values;
    }
}
