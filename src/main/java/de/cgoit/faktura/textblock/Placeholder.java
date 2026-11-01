package de.cgoit.faktura.textblock;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Placeholders that can be used in texts, written {@code {key}}. The keys are typed by the user and therefore German,
 * like the placeholders of number patterns.
 */
public enum Placeholder {
    /** Complete salutation line, e.g. "Sehr geehrter Herr Kirsch,". */
    SALUTATION("anrede"),
    /** Full name of the contact person. */
    CONTACT("ansprechpartner"),
    /** First name of the contact person; empty without a contact person. */
    CONTACT_FIRST_NAME("ansprechpartner_vorname"),
    /** Last name of the contact person; empty without a contact person. */
    CONTACT_LAST_NAME("ansprechpartner_nachname"),
    /** Name of the customer. */
    CUSTOMER("kunde"),
    CUSTOMER_NUMBER("kundennummer"),
    /** Reference of the customer on the document; empty without one. */
    CUSTOMER_REFERENCE("kundenreferenz"),
    /** Service period as "01.10.2026 - 31.12.2026"; empty without a period. */
    SERVICE_PERIOD("leistungszeitraum"),
    SERVICE_FROM("leistung_von"),
    SERVICE_TO("leistung_bis"),
    ISSUE_DATE("rechnungsdatum"),
    DUE_DATE("faelligkeit"),
    PAYMENT_DAYS("zahlungsziel"),
    NUMBER("rechnungsnummer"),
    /** Number of the offer; the same value as the document number, for offer texts. */
    OFFER_NUMBER("angebotsnummer"),
    /** Last day an offer is valid; empty for documents without validity. */
    VALID_UNTIL("gueltig_bis"),
    /** Gross total with currency, e.g. "119,00 €". */
    AMOUNT("betrag"),
    /** Name of the own company. */
    COMPANY("firma_name"),
    OWNER("firma_inhaber"),
    /** IBAN of the own company in groups of four. */
    IBAN("firma_iban"),
    BIC("firma_bic"),
    BANK("firma_bank"),
    /** Account holder: the owner, or the company name without an owner. */
    ACCOUNT_HOLDER("firma_kontoinhaber"),
    TAX_NUMBER("firma_steuernummer"),
    VAT_ID("firma_ustid"),
    COMPANY_EMAIL("firma_email"),
    COMPANY_PHONE("firma_telefon"),
    /** Logo of the company at this place; only filled in mails, empty without a logo file. */
    LOGO("logo", false, true),
    /** Name of the dunning level; only filled in dunning letters. */
    DUNNING_LEVEL("mahnstufe"),
    /** Open amount of the invoice; only filled in dunning letters. */
    OPEN_AMOUNT("offener_betrag"),
    /** Dunning fee, empty without a fee; only filled in dunning letters. */
    DUNNING_FEE("mahngebuehr"),
    /** Default interest, empty without interest; only filled in dunning letters. */
    INTEREST("verzugszinsen"),
    /** Open amount plus fee plus interest; only filled in dunning letters. */
    TOTAL_CLAIM("gesamtforderung"),
    /** Date by which the total claim is due; only filled in dunning letters. */
    NEW_DUE_DATE("neue_frist"),
    /** Days since the invoice fell due; only filled in dunning letters. */
    DAYS_OVERDUE("tage_ueberfaellig"),
    /** Number of the dunning letter; only filled in mails about dunning letters. */
    DUNNING_NUMBER("mahnungsnummer"),
    /** Number of all items in the notification mail; only filled in the notification digest. */
    NOTICE_COUNT("anzahl", true),
    /** Section about new drafts of recurring plans, empty without any; only filled in the notification digest. */
    NOTICE_DRAFTS("entwuerfe", true),
    /** Section about recurring plans that failed, empty without any; only filled in the notification digest. */
    NOTICE_PLAN_ERRORS("planfehler", true),
    /** Section about overdue invoices, empty without any; only filled in the notification digest. */
    NOTICE_OVERDUE("ueberfaellig", true),
    /** Section about invoices for which a dunning letter is due, empty without any; only in the digest. */
    NOTICE_DUNNING("mahnfaehig", true),
    /** Section about offers that expire soon, empty without any; only filled in the notification digest. */
    NOTICE_OFFERS("angebote", true),
    /** Link to the application, empty without a configured base URL; only filled in the notification digest. */
    NOTICE_LINK("link", true);

    /**
     * What {@link #LOGO} expands to in a mail text; the mail renderer turns it into the image.
     */
    public static final String LOGO_MARKER = "[logo]";

    private final String key;
    private final boolean digest;
    private final boolean mail;

    Placeholder(String key) {
        this(key, false, false);
    }

    Placeholder(String key, boolean digest) {
        this(key, digest, false);
    }

    Placeholder(String key, boolean digest, boolean mail) {
        this.key = key;
        this.digest = digest;
        this.mail = mail;
    }

    /**
     * Tells whether the placeholder only has a value in the notification mail to the operator.
     */
    public boolean digestOnly() {
        return digest;
    }

    /**
     * Returns the placeholders for texts about documents (invoice texts, dunning texts).
     */
    public static List<Placeholder> forDocuments() {
        return Arrays.stream(values())
                .filter(placeholder -> !placeholder.digest && !placeholder.mail)
                .toList();
    }

    /**
     * Returns the placeholders of document mails: those of documents plus the mail-only ones.
     */
    public static List<Placeholder> forMails() {
        return Arrays.stream(values())
                .filter(placeholder -> !placeholder.digest)
                .toList();
    }

    /**
     * Returns the placeholders of the notification mail: its own ones, the company, the owner and the logo.
     */
    public static List<Placeholder> forDigest() {
        return Arrays.stream(values())
                .filter(placeholder -> placeholder.digest || placeholder == COMPANY || placeholder == OWNER
                        || placeholder == LOGO)
                .toList();
    }

    public String key() {
        return key;
    }

    /**
     * Finds the placeholder with the given key.
     */
    public static Optional<Placeholder> ofKey(String key) {
        return Arrays.stream(values())
                .filter(placeholder -> placeholder.key.equals(key))
                .findFirst();
    }
}
