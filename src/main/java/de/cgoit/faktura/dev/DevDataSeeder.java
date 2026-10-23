package de.cgoit.faktura.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import de.cgoit.faktura.catalog.CatalogItemInput;
import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.catalog.ShippingOptionInput;
import de.cgoit.faktura.catalog.ShippingOptionService;
import de.cgoit.faktura.catalog.UnitService;
import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.customer.ContactInfo;
import de.cgoit.faktura.customer.ContactInput;
import de.cgoit.faktura.customer.CustomerInput;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.customer.EInvoiceMode;
import de.cgoit.faktura.document.FinalizeService;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.PaymentInput;
import de.cgoit.faktura.document.PaymentMethod;
import de.cgoit.faktura.document.PaymentService;
import de.cgoit.faktura.document.ShippingCharge;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.dunning.DunningLevelInput;
import de.cgoit.faktura.dunning.DunningLevelService;
import de.cgoit.faktura.home.SetupService;
import de.cgoit.faktura.home.SetupStep;
import de.cgoit.faktura.mail.MailScope;
import de.cgoit.faktura.mail.MailTemplateInput;
import de.cgoit.faktura.mail.MailTemplateService;
import de.cgoit.faktura.notification.NotificationInput;
import de.cgoit.faktura.notification.NotificationService;
import de.cgoit.faktura.recurring.PlanInput;
import de.cgoit.faktura.recurring.PlanItemInput;
import de.cgoit.faktura.recurring.RecurringInterval;
import de.cgoit.faktura.recurring.RecurringJobService;
import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.recurring.RecurringStatus;
import de.cgoit.faktura.tax.NewTaxProfile;
import de.cgoit.faktura.tax.TaxProfileService;
import de.cgoit.faktura.tax.TaxRegime;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateInput;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fills the empty development database with invented test data that covers many cases: both tax regimes, customers
 * with and without e-invoice, contacts, foreign and private customers, finalized, cancelled and draft invoices with
 * discounts, several tax rates and a multi-page invoice. Everything goes through the public services, so PDFs, XML,
 * hashes and numbers are real. All names, numbers and accounts are made up.
 */
@Component
@Profile("dev")
public class DevDataSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String MARKER = ".faktura-dev";
    private static final LocalDate SMALL_BUSINESS_FROM = LocalDate.of(2020, 1, 1);
    private static final LocalDate REGULAR_FROM = LocalDate.of(2027, 1, 1);

    private final CompanyService companies;
    private final TaxProfileService taxProfiles;
    private final CustomerService customers;
    private final CatalogService catalog;
    private final UnitService units;
    private final InvoiceService invoices;
    private final FinalizeService finalizer;
    private final PaymentService payments;
    private final SetupService setup;
    private final DunningLevelService dunningLevels;
    private final TextTemplateService texts;
    private final MailTemplateService mailTemplates;
    private final RecurringPlanService recurring;
    private final RecurringJobService recurringJob;
    private final NotificationService notifications;
    private final ShippingOptionService shippingOptions;
    private final String archivePath;

    public DevDataSeeder(CompanyService companies, TaxProfileService taxProfiles, CustomerService customers,
            CatalogService catalog, UnitService units, InvoiceService invoices, FinalizeService finalizer,
            PaymentService payments, SetupService setup,
            DunningLevelService dunningLevels, TextTemplateService texts, MailTemplateService mailTemplates,
            RecurringPlanService recurring,
            RecurringJobService recurringJob, NotificationService notifications, ShippingOptionService shippingOptions,
            @Value("${faktura.archive.path}") String archivePath) {
        this.companies = companies;
        this.taxProfiles = taxProfiles;
        this.customers = customers;
        this.catalog = catalog;
        this.units = units;
        this.invoices = invoices;
        this.finalizer = finalizer;
        this.payments = payments;
        this.setup = setup;
        this.dunningLevels = dunningLevels;
        this.texts = texts;
        this.mailTemplates = mailTemplates;
        this.recurring = recurring;
        this.recurringJob = recurringJob;
        this.notifications = notifications;
        this.shippingOptions = shippingOptions;
        this.archivePath = archivePath;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * Empties the archive directory and creates all test data. The database must be freshly migrated.
     *
     * @throws IllegalStateException if the archive path does not say {@code dev}
     */
    public void seed() {
        DevDatabaseReset.requireDevArchive(archivePath);
        clearArchive();
        createCompanyAndTax();
        createTexts();

        var club = customers.create(new CustomerInput("Muster Verein e.V.", "Teststraße 12", "54321", "Musterhausen",
                "DE", null, "rechnung@example.test", null, null, "Verein mit E-Rechnung", EInvoiceMode.ZUGFERD));
        var max = customers.addContact(club.id(), new ContactInput("MR", "Max", "Mustermann", "max@example.test",
                "+49 30 1234501", "+49 171 1234501", "Vorstand"));
        customers.addContact(club.id(), new ContactInput("MS", "Petra", "Probe", "petra@example.test", null,
                null, "Kassenwart"));
        var gmbh = customers.create(new CustomerInput("Beispiel GmbH", "Hauptstraße 1", "10115", "Berlin", "DE",
                "DE987654321", "buchhaltung@example.test", 30, 500, "Standardrabatt 5 %, Zahlungsziel 30 Tage",
                EInvoiceMode.NONE));
        var austria = customers.create(new CustomerInput("Alpen Consulting GmbH", "Ringstraße 5", "1010", "Wien",
                "AT", "ATU12345678", "office@example.test", null, null, "Kunde im Ausland", EInvoiceMode.ZUGFERD));
        var anna = customers.addContact(austria.id(), new ContactInput("MS", "Anna", "Beispielfrau", null, null,
                null, "Geschäftsführung"));
        var privat = customers.create(new CustomerInput("Anna Probemann", "Gartenweg 3", "80331", "München", "DE",
                null, null, null, null, "Privatkunde", EInvoiceMode.NONE));
        var former = customers.create(new CustomerInput("Ehemaliger Kunde GmbH", "Altstraße 9", "50667", "Köln", "DE",
                null, null, null, null, "Archiviert", EInvoiceMode.NONE));

        customers.archive(former.id());

        var catalogIds = createCatalog();

        createShippingOptions();

        smallBusinessInvoices(club.id(), max, gmbh.id(), austria.id(), anna, privat.id(), catalogIds);
        regularInvoices(club.id(), max, gmbh.id(), austria.id(), anna);
        createOffers(club.id(), max, gmbh.id());

        setDunningFees();
        createRecurringPlans(club.id(), gmbh.id());
        switchOnNotifications();

        for (var step : SetupStep.values()) {
            if (step.manual()) {
                setup.tick(step, true);
            }
        }

        LOG.info("Dev data created: {} customers, {} invoices", customers.search("", CustomerStatus.ALL).size(),
                invoices.list("", null).size());
    }

    /**
     * Two plans that start on the 1st of a month: a quarterly maintenance contract from next month and a monthly
     * hosting plan. The daily job runs once, so the page "Zur Freigabe" shows the drafts it generates (never finalized).
     */
    private void createRecurringPlans(String clubId, String gmbhId) {
        var today = LocalDate.now();

        var nextMonth = today.withDayOfMonth(1)
                .plusMonths(1);

        recurring.create(new PlanInput(gmbhId, "Wartungsvertrag", RecurringInterval.QUARTER, nextMonth, null, 45, RecurringStatus.ACTIVE, null, null, null, null, null, null,
                List.of(new PlanItemInput("Wartung und Hosting", 1000, "Std", 45_000, 0, null, null))));
        recurring.create(new PlanInput(clubId, "Webhosting", RecurringInterval.MONTH,
                LocalDate.of(today.getYear(), 1, 1), null, 14, RecurringStatus.ACTIVE, null, null, null, null, null,
                null, List.of(new PlanItemInput("Webhosting", 1000, "Std", 12_000, 0, null, null))));
        recurringJob.runDue(today);
    }

    /**
     * Switches on all notifications with the time of day 00:00, so the first check of the scheduler after the start
     * sends the mail to Mailpit; the settings page also has the button "check now".
     */
    private void switchOnNotifications() {
        notifications.update(new NotificationInput(true, true, true, true, true, null, "00:00"));
    }

    /**
     * The 1st dunning gets a fee of 2,50 EUR and the 2nd one of 5,00 EUR; the reminder stays free. No default interest.
     */
    private void setDunningFees() {
        for (var level : dunningLevels.list()) {
            var fee = switch (level.level()) {
                case 2 -> 250L;
                case 3 -> 500L;
                default -> level.feeCents();
            };

            dunningLevels.update(level.id(), new DunningLevelInput(level.level(), level.name(), level.daysAfterDue(), fee,
                    0, level.newDueDays(), level.headerText(), level.footerText(), level.active()));
        }
    }

    private void createCompanyAndTax() {
        companies.save(new CompanyInfo(null, "Beispiel Webservice", "Erika Beispiel", "Musterweg 1", "12345",
                "Beispielstadt", "DE", "info@example.test", "+49 30 1234567", "123/456/78901", "DE123456789",
                "DE02120300000000202051", "BYLADEM1001", "Testbank", 14));
        taxProfiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, SMALL_BUSINESS_FROM));
        taxProfiles.create(new NewTaxProfile(TaxRegime.REGULAR, REGULAR_FROM));
    }

    private void createTexts() {
        texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.HEADER, "Kurz",
                "{anrede}\n\nvielen Dank für Ihren Auftrag. Ich berechne Ihnen:", false));
        texts.create(new TextTemplateInput(TextScope.INVOICE, TextKind.FOOTER, "Mit Zahlungsfrist",
                "Bitte überweisen Sie **{betrag}** bis zum {faelligkeit} unter Angabe der Rechnungsnummer "
                        + "{rechnungsnummer} auf das Konto {firma_iban}.", false));
        texts.create(new TextTemplateInput(TextScope.OFFER, TextKind.HEADER, "Angebot",
                "{anrede}\n\nvielen Dank für Ihre Anfrage. Gerne biete ich Ihnen an:", true));
        texts.create(new TextTemplateInput(TextScope.OFFER, TextKind.FOOTER, "Angebot Fuß",
                "Das Angebot ist 30 Tage gültig.", true));
        texts.create(new TextTemplateInput(TextScope.DUNNING, TextKind.HEADER, "Zahlungserinnerung",
                "{anrede}\n\nleider konnte ich noch keinen Zahlungseingang feststellen.", true));
        mailTemplates.create(new MailTemplateInput(MailScope.INVOICE, "Kurz und knapp",
                "Rechnung {rechnungsnummer}",
                "{anrede}\n\nanbei die Rechnung **{rechnungsnummer}** über {betrag}, fällig am {faelligkeit}.\n\n"
                        + "- Zahlung auf {firma_iban}\n- Verwendungszweck: {rechnungsnummer}\n\nViele Grüße\n{firma_inhaber}",
                false));
    }

    private void createShippingOptions() {
        shippingOptions.create(new ShippingOptionInput("Päckchen", 495, null, 0, 10, true));
        shippingOptions.create(new ShippingOptionInput("Paket", 695, null, 0, 20, true));
        shippingOptions.create(new ShippingOptionInput("Kurierversand", 1490, null, 0, 30, false));
    }

    private CatalogIds createCatalog() {
        var hour = unitId("Std");
        var piece = unitId("Stk");
        var month = unitId("Mon");
        var flat = unitId("Psch");
        var day = unitId("Tag");
        var web = catalog.create(new CatalogItemInput("Webentwicklung", "Stundensatz", hour, 8500, null));

        catalog.create(new CatalogItemInput("Wartungspauschale", "Monatliche Wartung der Website", flat, 4900, null));
        catalog.create(new CatalogItemInput("Domain-Registrierung", "Jahresgebühr", piece, 1200, null));
        catalog.create(new CatalogItemInput("Hosting", "Webhosting je Monat", month, 1500, null));
        catalog.create(new CatalogItemInput("Beratung vor Ort", "Tagessatz", day, 60000, null));
        catalog.create(new CatalogItemInput("Fachbuch", "Mit 7 % Umsatzsteuer", piece, 2500, "S"));

        var old = catalog.create(new CatalogItemInput("Alte Leistung (inaktiv)", null, hour, 5000, null));

        catalog.deactivate(old.id());
        createMoreCatalogItems(hour, piece, month, flat, day);

        return new CatalogIds(web.id());
    }

    /**
     * Adds a larger invented catalog, so that searching the catalog in the invoice editor can be tried out.
     */
    private void createMoreCatalogItems(String hour, String piece, String month, String flat, String day) {
        var items = new Object[][] {
                { "Webdesign Relaunch", "Neugestaltung einer bestehenden Website", flat, 72000 },
                { "Webshop Einrichtung", "Einrichtung und Grundkonfiguration", flat, 129000 },
                { "Webanalyse Auswertung", "Monatlicher Bericht", month, 6900 },
                { "Webhosting Premium", "Mehr Speicher und tägliche Sicherung", month, 2900 },
                { "Webseiten-Check", "Technische Prüfung inklusive Bericht", flat, 19000 },
                { "SEO-Grundoptimierung", "Titel, Beschreibungen, Struktur", flat, 45000 },
                { "SEO-Beratung", "Stundensatz", hour, 9500 },
                { "Suchmaschinen-Eintrag", "Anmeldung bei Suchmaschinen", piece, 3500 },
                { "Online-Marketing Beratung", "Kampagnenplanung", hour, 9500 },
                { "Newsletter-Vorlage", "Gestaltung einer wiederverwendbaren Vorlage", piece, 24000 },
                { "Newsletter-Versand", "Betreuung je Versand", piece, 4500 },
                { "Social-Media-Betreuung", "Monatliche Pflege der Kanäle", month, 39000 },
                { "Logo-Entwurf", "Drei Entwürfe, eine Überarbeitung", flat, 68000 },
                { "Visitenkarten-Gestaltung", "Layout für den Druck", flat, 12000 },
                { "Flyer-Gestaltung", "Layout für den Druck", flat, 18000 },
                { "Broschüre Layout", "Je Seite", piece, 3500 },
                { "Fotobearbeitung", "Je Bild", piece, 800 },
                { "Bildretusche aufwendig", "Je Bild", piece, 2500 },
                { "Texterstellung", "Je Normseite", piece, 4500 },
                { "Lektorat", "Je Normseite", piece, 1800 },
                { "Übersetzung Deutsch-Englisch", "Je Normzeile", piece, 180 },
                { "App-Entwicklung", "Stundensatz", hour, 9500 },
                { "App-Wartung", "Monatliche Pauschale", month, 14900 },
                { "Schnittstellen-Anbindung", "Anbindung eines Fremdsystems", flat, 98000 },
                { "Datenbank-Einrichtung", "Installation und Grundkonfiguration", flat, 36000 },
                { "Datenmigration", "Stundensatz", hour, 8500 },
                { "Backup-Einrichtung", "Automatische Sicherung", flat, 22000 },
                { "Backup-Überwachung", "Monatliche Kontrolle", month, 2500 },
                { "Server-Administration", "Stundensatz", hour, 9000 },
                { "Server-Einrichtung", "Installation und Härtung", flat, 54000 },
                { "Firewall-Konfiguration", "Regeln und Dokumentation", flat, 31000 },
                { "IT-Sicherheitsberatung", "Tagessatz", day, 72000 },
                { "Schulung vor Ort", "Tagessatz", day, 68000 },
                { "Schulung online", "Je Stunde", hour, 7500 },
                { "Workshop Teamtag", "Ganztägig bis zu acht Personen", day, 89000 },
                { "Dokumentation erstellen", "Je Seite", piece, 3000 },
                { "Handbuch Anwender", "Je Seite", piece, 3500 },
                { "Anfahrt Pauschale", "Innerhalb von 30 km", flat, 4000 },
                { "Anfahrt je Kilometer", "Je Kilometer", piece, 70 },
                { "Hotline Monatspauschale", "Telefonische Unterstützung", month, 9900 },
                { "Support-Einsatz", "Stundensatz", hour, 7500 },
                { "Notdienst Zuschlag", "Außerhalb der Geschäftszeit", flat, 15000 },
                { "Lizenz Bürosoftware", "Jahreslizenz je Arbeitsplatz", piece, 8900 },
                { "Lizenz Grafiksoftware", "Jahreslizenz je Arbeitsplatz", piece, 24000 },
                { "Hardware Beschaffung", "Pauschale je Beschaffung", flat, 5000 },
                { "Router Einrichtung", "Je Gerät", piece, 6500 },
                { "Drucker Einrichtung", "Je Gerät", piece, 4500 },
                { "E-Mail-Postfach", "Je Postfach und Monat", month, 300 },
                { "E-Mail-Migration", "Je Postfach", piece, 2500 },
                { "Domain Übertragung", "Je Domain", piece, 1500 },
                { "Domain-Verwaltung", "Je Domain und Jahr", piece, 600 },
                { "SSL-Zertifikat", "Je Jahr", piece, 3900 },
                { "Cloud-Speicher 100 GB", "Je Monat", month, 400 },
                { "Cloud-Migration", "Stundensatz", hour, 9000 },
                { "Projektleitung", "Stundensatz", hour, 9500 },
                { "Anforderungsanalyse", "Tagessatz", day, 72000 },
                { "Konzepterstellung", "Tagessatz", day, 72000 },
                { "Testing und Abnahme", "Stundensatz", hour, 7500 },
                { "Code-Review", "Stundensatz", hour, 9500 },
                { "Performance-Analyse", "Je Analyse", flat, 38000 },
                { "Barrierefreiheit prüfen", "Prüfbericht je Seite", piece, 4500 },
                { "Datenschutz-Check Website", "Prüfung und Empfehlungen", flat, 29000 },
                { "Fachbuch Webentwicklung", "Mit 7 % Umsatzsteuer", piece, 3900 } };

        for (var item : items) {
            catalog.create(new CatalogItemInput((String) item[0], (String) item[1], (String) item[2],
                    ((Integer) item[3]).longValue(), null));
        }
    }

    private void smallBusinessInvoices(String clubId, ContactInfo max, String gmbhId, String austriaId,
            ContactInfo anna, String privateId, CatalogIds catalogIds) {
        var header = defaultText(TextKind.HEADER);
        var footer = defaultText(TextKind.FOOTER);

        // The example of the data model: 485,45 EUR with item discount and total discount of 5 %
        var paid = finalizeInvoice(new InvoiceInput(clubId, LocalDate.of(2026, 9, 5), LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30), null, "Webentwicklung September 2026", header, footer,
                TotalDiscount.percent(500), List.of(
                item("Webentwicklung", 6000, "Std", 8500, 1000, "E", 0, catalogIds.webItemId()),
                item("Domain-Registrierung", 1000, "Stk", 1200, 0, "E", 0, null),
                item("Anfahrt", 1000, "Psch", 4000, 0, "E", 0, null)), max.id()));

        // Finalized and then cancelled; the customer default discount (5 %) applies
        var cancelled = finalizeInvoice(new InvoiceInput(gmbhId, LocalDate.of(2026, 9, 20), null, null, null,
                "Beratung und Hosting", header, footer, null, List.of(
                item("Beratung vor Ort", 2000, "Tag", 60000, 0, "E", 0, null),
                item("Hosting", 3000, "Mon", 1500, 0, "E", 0, null)), null));

        finalizer.cancelInvoice(cancelled.id());

        // Foreign customer with e-invoice, fractional quantity
        var partlyPaid = finalizeInvoice(new InvoiceInput(austriaId, LocalDate.of(2026, 10, 5), null, null, 21,
                "Wartung Oktober", header,                footer, TotalDiscount.none(), List.of(
                item("Wartungspauschale", 1000, "Psch", 4900, 0, "E", 0, null),
                item("Webentwicklung\nStundenaufwand nach Absprache", 1500, "Std", 8500, 0, "E", 0, null)),
                anna.id()));

        // Private customer, total discount as an amount
        finalizeInvoice(new InvoiceInput(privateId, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 9),
                LocalDate.of(2026, 10, 9), null, "Einmalige Leistung", header, footer, TotalDiscount.amount(500),
                List.of(item("Webentwicklung", 3000, "Std", 8500, 0, "E", 0, null)), null, "BE-2026-017",
                new ShippingCharge("Päckchen", 495, "E", 0)));

        // Payments: the first invoice is paid in full, the Austrian one in part
        payments.record(paid.id(), new PaymentInput(LocalDate.of(2026, 9, 18), paid.grossTotalCents(),
                PaymentMethod.BANK_TRANSFER, "Überweisung " + paid.number(), null));
        payments.record(partlyPaid.id(), new PaymentInput(LocalDate.of(2026, 10, 15), 10_000,
                PaymentMethod.BANK_TRANSFER, "Anzahlung", "Rest folgt"));

        // Long overdue and unpaid: the dunning page suggests a letter
        finalizeInvoice(new InvoiceInput(gmbhId, LocalDate.of(2026, 8, 10), null, null, null,
                "Hosting August", header, footer, TotalDiscount.none(), List.of(
                item("Hosting", 1000, "Mon", 1500, 0, "E", 0, null)), null));

        // Draft for the review dialog (e-invoice customer, contact person)
        invoices.createDraft(new InvoiceInput(clubId, LocalDate.of(2026, 10, 20), null, null, null,
                "Entwurf zum Prüfen", header, footer, TotalDiscount.none(), List.of(
                item("Webentwicklung", 4000, "Std", 8500, 0, "E", 0, null)), max.id()));
    }

    private void regularInvoices(String clubId, ContactInfo max, String gmbhId, String austriaId, ContactInfo anna) {
        var header = defaultText(TextKind.HEADER);
        var footer = defaultText(TextKind.FOOTER);

        // Regular taxation: 19 %, 7 %, 0 % and a total discount as an amount, shared between the rates
        finalizeInvoice(new InvoiceInput(clubId, LocalDate.of(2027, 1, 15), LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 1, 31), null, "Januar 2027", header, footer, TotalDiscount.amount(5000), List.of(
                item("Webentwicklung", 10000, "Std", 8500, 0, "S", 1900, null),
                item("Fachbuch", 2000, "Stk", 2500, 0, "S", 700, null),
                item("Auslagen ohne Steuer", 1000, "Psch", 3000, 0, "Z", 0, null)), max.id(), "PO-2027-001",
                new ShippingCharge("Paket", 695, "S", 1900)));

        // A long invoice: several pages in the PDF
        var many = new ArrayList<InvoiceItemInput>();

        for (var i = 1; i <= 40; i++) {
            many.add(item("Position " + i + "\nBeschreibung zu Position " + i, 1000, "Std", 8500, 0, "S", 1900,
                    null));
        }

        finalizeInvoice(new InvoiceInput(gmbhId, LocalDate.of(2027, 2, 1), null, null, null,
                "Projektabrechnung", header,                footer, TotalDiscount.percent(300), many, null));

        // Draft with reverse charge for a foreign customer
        invoices.createDraft(new InvoiceInput(austriaId, LocalDate.of(2027, 2, 10), null, null, null,
                "Leistung im Ausland", header, footer, TotalDiscount.none(), List.of(
                item("Beratung", 1000, "Tag", 60000, 0, "AE", 0, null)), anna.id()));
    }

    /**
     * Offers in three states: open (valid for weeks), expired, and a draft for the e-invoice customer, who gets no
     * e-invoice for an offer.
     */
    private void createOffers(String clubId, ContactInfo max, String gmbhId) {
        var header = offerText(TextKind.HEADER);
        var footer = offerText(TextKind.FOOTER);
        var today = LocalDate.now();

        finalizeOffer(new InvoiceInput(gmbhId, today.minusDays(3), null, null, null, "Relaunch Webseite", header,
                footer, TotalDiscount.percent(500), List.of(
                item("Konzept und Design", 16000, "Std", 8500, 0, "E", 0, null),
                item("Umsetzung", 40000, "Std", 8500, 1000, "E", 0, null)), null, "ANF-2026-14",
                new ShippingCharge("Pauschale Reise", 4500, "E", 0), null));
        finalizeOffer(new InvoiceInput(gmbhId, LocalDate.of(2026, 8, 3), null, null, null, "Wartungsvertrag", header,
                footer, TotalDiscount.none(), List.of(
                item("Wartung Webseite", 1000, "Mon", 12000, 0, "E", 0, null)), null, null, null,
                LocalDate.of(2026, 8, 31)));
        invoices.createOfferDraft(new InvoiceInput(clubId, today, null, null, null, "Angebot Vereinsseite", header,
                footer, TotalDiscount.none(), List.of(
                item("Webentwicklung", 8000, "Std", 8500, 0, "E", 0, null)), max.id()));
    }

    private InvoiceInfo finalizeOffer(InvoiceInput input) {
        var draft = invoices.createOfferDraft(input);

        return finalizer.finalizeInvoice(draft.id());
    }

    private String offerText(TextKind kind) {
        return texts.defaultBody(TextScope.OFFER, kind)
                .orElse(null);
    }

    private String defaultText(TextKind kind) {
        return texts.defaultBody(TextScope.INVOICE, kind)
                .orElse(null);
    }

    private InvoiceInfo finalizeInvoice(InvoiceInput input) {
        var draft = invoices.createDraft(input);

        return finalizer.finalizeInvoice(draft.id());
    }

    private String unitId(String code) {
        return units.list().stream()
                .filter(unit -> unit.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private static InvoiceItemInput item(String description, long quantityThousandths, String unitCode,
            long unitPriceCents, int discountBp, String category, int rateBp, String catalogItemId) {
        return new InvoiceItemInput(description, quantityThousandths, unitCode, unitPriceCents, discountBp, category,
                rateBp, catalogItemId);
    }

    private void clearArchive() {
        var root = Path.of(archivePath).toAbsolutePath().normalize();
        var marker = root.resolve(MARKER);

        try {
            Files.createDirectories(root);

            if (!Files.exists(marker) && !isEmpty(root)) {
                throw new IllegalStateException("The archive directory is not empty and has no " + MARKER
                        + " marker; it is not emptied");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare the dev archive", e);
        }

        try (var files = Files.walk(root)) {
            for (var file : files.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.equals(root)) {
                    Files.delete(file);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot empty the dev archive", e);
        }

        try {
            Files.writeString(marker, "Created by the dev profile; its content is deleted on every start.\n");
        } catch (IOException e) {
            throw new IllegalStateException("Cannot mark the dev archive", e);
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private record CatalogIds(String webItemId) {
    }
}
