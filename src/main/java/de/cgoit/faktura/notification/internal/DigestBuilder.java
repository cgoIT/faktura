package de.cgoit.faktura.notification.internal;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.OfferSummary;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.notification.NotificationCategory;
import de.cgoit.faktura.notification.NotificationSettings;
import de.cgoit.faktura.notification.internal.NotificationRepository.Item;
import de.cgoit.faktura.recurring.RecurringPlanService;
import de.cgoit.faktura.recurring.RecurringStatus;
import de.cgoit.faktura.render.RichText;
import de.cgoit.faktura.shared.MoneyFormatter;
import de.cgoit.faktura.textblock.Placeholder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

/**
 * Collects what the daily mail has to report and writes the sections of the mail. Everything is computed from the
 * current data: pending drafts, failed plans, overdue invoices, dunning suggestions and expiring offers, minus what
 * an earlier mail already reported. A section is empty when its category is switched off or has nothing new.
 *
 * <p>The section texts carry their own markup (a bold heading and a list); every value in them is escaped.
 */
@Component
public class DigestBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String NO_STAGE = "0";

    private final RecurringPlanService plans;
    private final InvoiceService invoices;
    private final OfferService offers;
    private final DunningService dunnings;
    private final CompanyService company;
    private final NotificationRepository repository;
    private final NotificationProperties properties;
    private final MoneyFormatter money;
    private final MessageSource messages;
    private final String baseUrl;

    public DigestBuilder(RecurringPlanService plans, InvoiceService invoices, OfferService offers,
            DunningService dunnings, CompanyService company, NotificationRepository repository,
            NotificationProperties properties, MoneyFormatter money, MessageSource messages, @Value("${faktura.base-url:}") String baseUrl) {
        this.plans = plans;
        this.invoices = invoices;
        this.offers = offers;
        this.dunnings = dunnings;
        this.company = company;
        this.repository = repository;
        this.properties = properties;
        this.money = money;
        this.messages = messages;
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    /**
     * What a mail contains.
     *
     * @param items  the reported items, to be logged once the mail is sent
     * @param values the values of the placeholders of the mail template
     */
    public record Digest(List<Item> items, Map<Placeholder, String> values) {

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    /**
     * Builds the mail for the given day.
     */
    public Digest build(NotificationSettings settings, LocalDate today) {
        var items = new ArrayList<Item>();
        var values = new EnumMap<Placeholder, String>(Placeholder.class);

        values.put(Placeholder.NOTICE_DRAFTS, settings.draftEnabled() ? drafts(items) : "");
        values.put(Placeholder.NOTICE_PLAN_ERRORS, settings.planErrorEnabled() ? planErrors(items) : "");
        values.put(Placeholder.NOTICE_OVERDUE, settings.overdueEnabled() ? overdue(items) : "");
        values.put(Placeholder.NOTICE_DUNNING, settings.dunningEnabled() ? dunning(items) : "");
        values.put(Placeholder.NOTICE_OFFERS, settings.offerEnabled() ? expiringOffers(items) : "");
        values.put(Placeholder.NOTICE_COUNT, String.valueOf(items.size()));
        values.put(Placeholder.NOTICE_LINK, RichText.escape(baseUrl));

        var info = company.find();

        values.put(Placeholder.COMPANY, RichText.escape(info.map(CompanyInfo::name).orElse("")));
        values.put(Placeholder.OWNER, RichText.escape(info.map(CompanyInfo::owner).orElse("")));

        return new Digest(List.copyOf(items), values);
    }

    private String drafts(List<Item> items) {
        var reported = repository.reported(NotificationCategory.DRAFT);
        var entries = plans.pendingReleases()
                .stream()
                .filter(entry -> !reported.contains(entry.documentId() + "|" + NO_STAGE))
                .toList();

        return section(NotificationCategory.DRAFT, "notification.section.draft", entries, items,
                entry -> entry.documentId(), entry -> NO_STAGE,
                entry -> line("notification.item.draft", entry.customerName(), entry.planName(),
                        DATE.format(entry.serviceFrom()) + " - " + DATE.format(entry.serviceTo()),
                        money.format(entry.grossCents())));
    }

    private String planErrors(List<Item> items) {
        var reported = repository.reported(NotificationCategory.PLAN_ERROR);
        var entries = plans.list()
                .stream()
                .filter(plan -> plan.status() == RecurringStatus.ACTIVE && plan.lastError() != null)
                .filter(plan -> !reported.contains(plan.id() + "|" + plan.lastError()))
                .toList();

        return section(NotificationCategory.PLAN_ERROR, "notification.section.planError", entries, items,
                plan -> plan.id(), plan -> plan.lastError(),
                plan -> line("notification.item.planError", plan.name(), text(plan.lastError())));
    }

    private String overdue(List<Item> items) {
        var reported = repository.reported(NotificationCategory.OVERDUE);
        var entries = invoices.listOverdue()
                .stream()
                .filter(invoice -> !reported.contains(invoice.id() + "|" + NO_STAGE))
                .toList();

        return section(NotificationCategory.OVERDUE, "notification.section.overdue", entries, items,
                invoice -> invoice.id(), invoice -> NO_STAGE,
                invoice -> line("notification.item.overdue", invoice.number(), invoice.customerName(),
                        money.format(invoice.balance().openCents()),
                        String.valueOf(invoice.balance().daysOverdue())));
    }

    private String dunning(List<Item> items) {
        var reported = repository.reported(NotificationCategory.DUNNING_DUE);
        var entries = dunnings.suggestions()
                .stream()
                .filter(suggestion -> !reported.contains(suggestion.invoiceId() + "|" + suggestion.level().level()))
                .toList();

        return section(NotificationCategory.DUNNING_DUE, "notification.section.dunning", entries, items,
                suggestion -> suggestion.invoiceId(), suggestion -> String.valueOf(suggestion.level().level()),
                suggestion -> line("notification.item.dunning", suggestion.invoiceNumber(),
                        suggestion.customerName(), suggestion.level().name()));
    }

    private String expiringOffers(List<Item> items) {
        var reported = repository.reported(NotificationCategory.OFFER_EXPIRING);
        var entries = offers.expiringSoon()
                .stream()
                .filter(offer -> !reported.contains(offer.id() + "|" + NO_STAGE))
                .toList();

        return section(NotificationCategory.OFFER_EXPIRING, "notification.section.offer", entries, items,
                OfferSummary::id, offer -> NO_STAGE,
                offer -> line("notification.item.offer", offer.number(), offer.customerName(),
                        money.format(offer.grossTotalCents()), DATE.format(offer.validUntil())));
    }

    /**
     * Writes one section and adds the listed entries to the items to log. Entries beyond the limit are not listed
     * and not logged, so a later mail reports them.
     */
    private <T> String section(NotificationCategory category, String headingKey, List<T> entries, List<Item> items,
            Function<T, String> objectId, Function<T, String> stage, Function<T, String> line) {
        if (entries.isEmpty()) {
            return "";
        }

        var shown = entries.subList(0, Math.min(entries.size(), properties.maxListRows()));
        var text = new StringBuilder(messages.getMessage(headingKey, new Object[] {entries.size()}, Locale.GERMAN));

        for (var entry : shown) {
            text.append("\n- ")
                    .append(line.apply(entry));
            items.add(new Item(category, objectId.apply(entry), stage.apply(entry)));
        }

        if (entries.size() > shown.size()) {
            text.append("\n")
                    .append(messages.getMessage("notification.more", new Object[] {entries.size() - shown.size()},
                            Locale.GERMAN));
        }

        return text.toString();
    }

    private String line(String key, String... arguments) {
        var escaped = new Object[arguments.length];

        for (var i = 0; i < arguments.length; i++) {
            escaped[i] = RichText.escape(arguments[i] == null ? "" : arguments[i]);
        }

        return messages.getMessage(key, escaped, Locale.GERMAN);
    }

    private String text(String key) {
        return messages.getMessage(key, null, key, Locale.GERMAN);
    }
}
