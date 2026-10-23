package de.cgoit.faktura.document.internal;

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;

import de.cgoit.faktura.document.internal.DocumentRepository.Header;
import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.shared.Iban;
import de.cgoit.faktura.shared.MoneyFormatter;
import de.cgoit.faktura.textblock.Placeholder;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.stereotype.Component;

/**
 * Values for the placeholders of header and footer text. Only data of the snapshots and of the document is used, so
 * a finalized document gets the same text no matter what happens to the master data later. The values are escaped
 * for the rich text renderer.
 */
@Component
public class PlaceholderValues {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private final TextTemplateService texts;
    private final MoneyFormatter money;

    public PlaceholderValues(TextTemplateService texts, MoneyFormatter money) {
        this.texts = texts;
        this.money = money;
    }

    /**
     * Builds the values for an invoice.
     *
     * @param number the invoice number, {@code null} while it has none
     */
    public Map<Placeholder, String> build(Header header, SenderSnapshot sender, RecipientSnapshot recipient,
            long grossCents, String number) {
        var values = new EnumMap<Placeholder, String>(Placeholder.class);
        var from = header.serviceFrom() == null ? "" : DATE.format(header.serviceFrom());
        var to = header.serviceTo() == null ? "" : DATE.format(header.serviceTo());

        values.put(Placeholder.SALUTATION, texts.salutation(recipient.contactSalutation(),
                recipient.contactLastName(), recipient.contactName()));
        values.put(Placeholder.CONTACT, recipient.contactName() == null ? "" : recipient.contactName());
        values.put(Placeholder.CONTACT_FIRST_NAME, orEmpty(recipient.contactFirstName()));
        values.put(Placeholder.CONTACT_LAST_NAME, orEmpty(recipient.contactLastName()));
        values.put(Placeholder.CUSTOMER, recipient.name());
        values.put(Placeholder.CUSTOMER_NUMBER, recipient.number());
        values.put(Placeholder.CUSTOMER_REFERENCE, orEmpty(header.customerReference()));
        values.put(Placeholder.SERVICE_FROM, from);
        values.put(Placeholder.SERVICE_TO, to);
        values.put(Placeholder.SERVICE_PERIOD, from.isEmpty() ? "" : from + " - " + to);
        values.put(Placeholder.ISSUE_DATE, DATE.format(header.issueDate()));
        values.put(Placeholder.DUE_DATE, header.dueDate() == null ? "" : DATE.format(header.dueDate()));
        values.put(Placeholder.PAYMENT_DAYS, header.dueDate() == null
                ? ""
                : String.valueOf(ChronoUnit.DAYS.between(header.issueDate(), header.dueDate())));
        values.put(Placeholder.NUMBER, number == null ? "" : number);
        values.put(Placeholder.OFFER_NUMBER, number == null ? "" : number);
        values.put(Placeholder.VALID_UNTIL, header.validUntil() == null ? "" : DATE.format(header.validUntil()));
        values.put(Placeholder.AMOUNT, money.format(grossCents));
        values.put(Placeholder.COMPANY, sender.name());
        values.put(Placeholder.OWNER, orEmpty(sender.owner()));
        values.put(Placeholder.IBAN, orEmpty(Iban.format(sender.iban())));
        values.put(Placeholder.BIC, orEmpty(sender.bic()));
        values.put(Placeholder.BANK, orEmpty(sender.bankName()));
        values.put(Placeholder.ACCOUNT_HOLDER, sender.owner() == null || sender.owner().isBlank()
                ? sender.name()
                : sender.owner());
        values.put(Placeholder.TAX_NUMBER, orEmpty(sender.taxNumber()));
        values.put(Placeholder.VAT_ID, orEmpty(sender.vatId()));
        values.put(Placeholder.COMPANY_EMAIL, orEmpty(sender.email()));
        values.put(Placeholder.COMPANY_PHONE, orEmpty(sender.phone()));

        values.replaceAll((placeholder, value) -> RichText.escape(value));

        return values;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
