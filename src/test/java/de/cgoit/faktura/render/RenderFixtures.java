package de.cgoit.faktura.render;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import de.cgoit.faktura.render.RenderModel.Account;
import de.cgoit.faktura.render.RenderModel.Item;
import de.cgoit.faktura.render.RenderModel.Kind;
import de.cgoit.faktura.render.RenderModel.Party;
import de.cgoit.faktura.render.RenderModel.TaxLine;
import de.cgoit.faktura.render.internal.RenderProperties;
import de.cgoit.faktura.shared.MoneyFormatter;
import org.springframework.context.support.ResourceBundleMessageSource;

/**
 * Invented sample data for renderer tests; nothing here belongs to a real person or company.
 */
public final class RenderFixtures {

    static final String FOOTER_NAME = "Beispiel Nebengewerbe";

    private RenderFixtures() {
    }

    public static InvoiceRenderer renderer() {
        return new InvoiceRenderer(messages(), properties(), new GirocodeService(), new MoneyFormatter());
    }

    public static DunningRenderer dunningRenderer() {
        return new DunningRenderer(messages(), properties(), new GirocodeService(), new MoneyFormatter());
    }

    private static ResourceBundleMessageSource messages() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("messages");
        messages.setDefaultEncoding("UTF-8");
        messages.setFallbackToSystemLocale(false);

        return messages;
    }

    private static RenderProperties properties() {
        return new RenderProperties(null, "Ich bedanke mich für Ihren Auftrag",
                "Hiermit storniere ich die Rechnung {0} vom {1}.");
    }

    /**
     * A dunning letter for an invoice of 100,00 € with a fee and interest.
     */
    public static DunningRenderModel dunning(boolean draft) {
        return new DunningRenderModel(draft, draft ? null : "MA202700001", LocalDate.of(2027, 3, 1), "1. Mahnung",
                sender(), recipient(), "KD0001", "RE202700001", LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 29),
                10_000, 2_500, 7_500, 500, 123, 8_123, LocalDate.of(2027, 3, 8),
                "Sehr geehrter Herr Mustermann,\n\nich konnte keinen Zahlungseingang feststellen.",
                "Bitte überweisen Sie die Gesamtforderung bis zum 08.03.2027.", account());
    }

    static Party sender() {
        return new Party(FOOTER_NAME, "Erika Beispiel", "Musterweg 1", "12345", "Beispielstadt", "DE",
                "info@example.test", "+49 30 1234567", "123/456/78901", null);
    }

    static Party recipient() {
        return new Party("Muster Verein e.V.", "Max Mustermann", "Teststraße 12", "54321", "Musterhausen", "DE",
                null, null, null, null);
    }

    static Account account() {
        return new Account("Erika Beispiel", "Testbank", "DE02120300000000202051", "BYLADEM1001");
    }

    /**
     * An invoice with 10,00 net and 19 % tax.
     */
    public static RenderModel regularInvoice() {
        var item = new Item(1, "Beratung", 1000, "Stunde", "Stunden", 1000, 0, 1900, 1000);

        return new RenderModel(Kind.INVOICE, false, "RE202700002", LocalDate.of(2027, 2, 1), null, null,
                LocalDate.of(2027, 2, 15), sender(), recipient(), "KD0001", null, null, List.of(item), 1000, 0,
                1000, 190, 1190, List.of(new TaxLine(1900, 1000, 190)), null, null, account(), null, null);
    }

    /**
     * An invoice with the given number of items, one line each of {@code 10,00 €} plus description text.
     */
    static RenderModel invoice(int itemCount) {
        var items = new ArrayList<Item>();

        for (var i = 1; i <= itemCount; i++) {
            items.add(new Item(i, "Position " + i + " Arbeitszeit\nBeschreibung zu Position " + i + " mit Umlauten äöüß",
                    1000, "Stunde", "Stunden", 1000, 0, 0, 1000));
        }

        long total = itemCount * 1000L;

        return new RenderModel(Kind.INVOICE, false, "RE202700001", LocalDate.of(2027, 1, 15),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 1, 29), sender(), recipient(),
                "KD0001", "Sehr geehrter Herr Mustermann,\n\nich stelle Ihnen folgende Leistungen in Rechnung:",
                "Ich danke für die gute Zusammenarbeit.\n\nBitte überweisen Sie den Betrag in Höhe von 20,00 € unter Angabe der Rechnungsnummer RE202700001 auf das Konto\n\nDE02 1203 0000 0000 2020 51 bei der Testbank, BIC BYLADEM1001, Kontoinhaber Erika Beispiel.\n\nZahlbar innerhalb von 14 Tagen (spätestens bis zum 29.01.2027) ohne Abzug.", List.copyOf(items), total, 0, total, 0, total, List.<TaxLine>of(),
                "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet.", null, account(), null, null);
    }
}
