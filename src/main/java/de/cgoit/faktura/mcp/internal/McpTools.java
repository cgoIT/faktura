package de.cgoit.faktura.mcp.internal;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import de.cgoit.faktura.catalog.CatalogService;
import de.cgoit.faktura.customer.CustomerService;
import de.cgoit.faktura.customer.CustomerStatus;
import de.cgoit.faktura.document.DocumentStatus;
import de.cgoit.faktura.document.DocumentType;
import de.cgoit.faktura.document.InvoiceInfo;
import de.cgoit.faktura.document.InvoiceInput;
import de.cgoit.faktura.document.InvoiceItemInput;
import de.cgoit.faktura.document.InvoiceService;
import de.cgoit.faktura.document.InvoiceSummary;
import de.cgoit.faktura.document.OfferService;
import de.cgoit.faktura.document.TotalDiscount;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.mcp.McpScope;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextKind;
import de.cgoit.faktura.textblock.TextScope;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tools the MCP server offers: reading customers, catalog, open invoices, dunning suggestions and expiring
 * offers, and creating invoice and offer drafts. Nothing here finalizes, cancels, books a payment, sends a mail or
 * deletes; a person does that in the web interface after checking the draft. Results carry only what the question
 * needs (no bank data, phone numbers or notes), at most {@value #LIMIT} rows each. Nothing is logged.
 */
@Component
@ConditionalOnProperty(name = "faktura.mcp.enabled", havingValue = "true")
public class McpTools {

    static final int LIMIT = 25;

    private static final Pattern DECIMAL = Pattern.compile("-?\\d{1,12}(\\.\\d{1,6})?");

    private static final String QUERY_SCHEMA = """
            {"type":"object","properties":{"query":{"type":"string","description":"Search text, empty for all"}}}""";

    private static final String DRAFT_SCHEMA = """
            {"type":"object","required":["customerId","items"],"properties":{
              "customerId":{"type":"string","description":"Customer id from search_customers"},
              "subject":{"type":"string","description":"Internal designation or note of the document, not printed on it (the web form calls it 'Interne Bezeichnung'); use it for an 'internal note' (interne Bemerkung), optional"},
              "paymentDays":{"type":"integer","minimum":0,"maximum":365,"description":"Payment term in days; default is the customer's or the company's term"},
              "totalDiscountPercent":{"type":"string","description":"Discount on the whole document in percent, e.g. 5; optional"},
              "customerReference":{"type":"string","description":"Reference of the customer, optional"},
              "serviceFrom":{"type":"string","description":"Start of the service period, YYYY-MM-DD, optional"},
              "serviceTo":{"type":"string","description":"End of the service period, YYYY-MM-DD, with serviceFrom"},
              "items":{"type":"array","minItems":1,"items":{"type":"object","properties":{
                "catalogItemId":{"type":"string","description":"Catalog entry id from search_catalog; fills description, unit, price and tax"},
                "description":{"type":"string","description":"Text of the item; required without catalogItemId"},
                "quantity":{"type":"string","description":"Quantity as a decimal number with a dot, e.g. 10 or 1.5; default 1"},
                "unitPrice":{"type":"string","description":"Net price per unit in euros, e.g. 85.00; required without catalogItemId"},
                "discountPercent":{"type":"string","description":"Item discount in percent, e.g. 10; optional"}}}}}}""";

    private final CustomerService customers;
    private final CatalogService catalog;
    private final InvoiceService invoices;
    private final OfferService offers;
    private final DunningService dunning;
    private final TextTemplateService texts;
    private final MessageSource messages;
    private final Clock clock;
    private final String baseUrl;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public McpTools(CustomerService customers, CatalogService catalog, InvoiceService invoices, OfferService offers,
            DunningService dunning, TextTemplateService texts, MessageSource messages, Clock clock,
            @Value("${faktura.base-url:}") String baseUrl) {
        this.customers = customers;
        this.catalog = catalog;
        this.invoices = invoices;
        this.offers = offers;
        this.dunning = dunning;
        this.texts = texts;
        this.messages = messages;
        this.clock = clock;
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");

        register("search_customers", "Finds active customers by number, name, city or e-mail.", QUERY_SCHEMA,
                arguments -> searchCustomers(arguments(arguments, Query.class)));
        register("search_catalog", "Finds active entries of the service catalog (number, name, unit, net price).",
                QUERY_SCHEMA, arguments -> searchCatalog(arguments(arguments, Query.class)));
        register("list_unpaid_invoices", "Lists finalized invoices that are not fully paid, oldest due date first.",
                """
                {"type":"object","properties":{"onlyOverdue":{"type":"boolean","description":"Only overdue invoices"}}}""",
                arguments -> unpaid(arguments(arguments, Unpaid.class)));
        register("get_invoice", "Shows one invoice or draft with its items and totals.",
                """
                {"type":"object","required":["id"],"properties":{"id":{"type":"string"}}}""",
                arguments -> invoice(arguments(arguments, Id.class)));
        register("list_dunning_suggestions", "Lists overdue invoices for which a dunning letter is due.",
                """
                {"type":"object","properties":{}}""", arguments -> dunningSuggestions());
        register("list_expiring_offers", "Lists offers without a decision that end soon.",
                """
                {"type":"object","properties":{}}""", arguments -> expiringOffers());
        register("create_invoice_draft", "Creates an invoice DRAFT. It is neither numbered nor sent; a person checks "
                + "and finalizes it in the web interface. Tax and payment term come from the tax profile and the customer.",
                DRAFT_SCHEMA, true, arguments -> draft(DocumentType.INVOICE, arguments(arguments, Draft.class)));
        register("create_offer_draft", "Creates an offer DRAFT. It is neither numbered nor sent; a person checks "
                + "and finalizes it in the web interface.",
                DRAFT_SCHEMA, true, arguments -> draft(DocumentType.OFFER, arguments(arguments, Draft.class)));
    }

    /**
     * Describes the tools a token with that scope may use, for {@code tools/list}.
     */
    public List<ToolInfo> list(McpScope scope) {
        return tools.values().stream()
                .filter(tool -> !tool.writes() || scope.allowsWriting())
                .map(Tool::info)
                .toList();
    }

    /**
     * Runs a tool. A violated business rule or malformed input is a result with {@code isError}, not an exception,
     * so the assistant can read the reason and correct itself.
     *
     * @throws IllegalArgumentException if no tool has that name
     */
    public ToolResult call(String name, Map<String, Object> arguments, McpScope scope) {
        var tool = tools.get(name);

        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool");
        }

        if (tool.writes() && !scope.allowsWriting()) {
            return error(messages.getMessage("mcp.error.scope", null, Locale.GERMAN));
        }

        try {
            return new ToolResult(List.of(new Text(mapper.writeValueAsString(tool.handler().apply(arguments)))), false);
        } catch (RuleViolationException e) {
            return error(e.violations().stream()
                    .map(violation -> (violation.field() == null ? "" : violation.field() + ": ")
                            + messages.getMessage(violation.key(), null, violation.key(), Locale.GERMAN))
                    .collect(Collectors.joining("; ")));
        } catch (DateTimeParseException | ArithmeticException | IllegalArgumentException | JacksonException e) {
            return error(messages.getMessage("mcp.error.invalidInput", null, Locale.GERMAN));
        }
    }

    private Page<CustomerRow> searchCustomers(Query query) {
        return page(customers.search(query.query(), CustomerStatus.ACTIVE), customer ->
                new CustomerRow(customer.id(), customer.number(), customer.name(), customer.zip(), customer.city()));
    }

    private Page<CatalogRow> searchCatalog(Query query) {
        return page(catalog.search(query.query(), true), item -> new CatalogRow(item.id(), item.number(),
                item.name(), item.unitCode(), euros(item.unitPriceCents()), item.taxCategory()));
    }

    private Page<InvoiceRow> unpaid(Unpaid unpaid) {
        var onlyOverdue = Boolean.TRUE.equals(unpaid.onlyOverdue());
        var rows = invoices.list("", DocumentStatus.FINAL).stream()
                .filter(row -> row.type() == DocumentType.INVOICE)
                .filter(row -> row.balance() != null && row.balance().openCents() > 0)
                .filter(row -> !onlyOverdue || row.balance().overdue())
                .sorted(Comparator.comparing(InvoiceSummary::dueDate))
                .toList();

        return page(rows, row -> new InvoiceRow(row.id(), row.number(), row.customerName(), row.subject(),
                row.issueDate().toString(), row.dueDate().toString(), euros(row.grossTotalCents()),
                euros(row.balance().openCents()), row.balance().overdue(), row.balance().daysOverdue()));
    }

    private InvoiceDetail invoice(Id id) {
        var info = invoices.find(id.id())
                .orElseThrow(() -> new RuleViolationException("invoice.error.unknown"));
        var name = info.recipientName() != null
                ? info.recipientName()
                : customers.find(info.customerId())
                        .map(customer -> customer.name())
                        .orElse("");
        var items = info.items().stream()
                .map(item -> new ItemRow(item.position(), item.description(),
                        BigDecimal.valueOf(item.quantityThousandths(), 3).stripTrailingZeros().toPlainString(),
                        item.unitCode(), euros(item.unitPriceCents()), BigDecimal.valueOf(item.discountBp(), 2)
                                .stripTrailingZeros().toPlainString(), euros(item.lineTotalCents())))
                .toList();

        return new InvoiceDetail(info.id(), info.number(), info.type().name(), info.status().name(), name,
                iso(info.issueDate()), iso(info.serviceFrom()), iso(info.serviceTo()), iso(info.dueDate()),
                info.subject(), items, euros(info.netTotalCents()), euros(info.taxTotalCents()),
                euros(info.grossTotalCents()));
    }

    private Page<DunningRow> dunningSuggestions() {
        return page(dunning.suggestions(), suggestion -> new DunningRow(suggestion.invoiceId(),
                suggestion.invoiceNumber(), suggestion.customerName(), iso(suggestion.dueDate()),
                suggestion.daysOverdue(), suggestion.level().name(), euros(suggestion.amounts().totalCents())));
    }

    private Page<OfferRow> expiringOffers() {
        return page(offers.expiringSoon(), offer -> new OfferRow(offer.id(), offer.number(), offer.customerName(),
                offer.state().name(), iso(offer.validUntil()), euros(offer.grossTotalCents())));
    }

    private DraftResult draft(DocumentType type, Draft draft) {
        if (draft.items() == null || draft.items().isEmpty()) {
            throw new IllegalArgumentException("No items");
        }

        var offer = type == DocumentType.OFFER;
        var issueDate = LocalDate.now(clock);
        var from = date(draft.serviceFrom());
        var to = date(draft.serviceTo());
        var day = from != null ? from : issueDate;
        var items = draft.items().stream()
                .map(item -> item(item, day))
                .toList();
        var scope = offer ? TextScope.OFFER : TextScope.INVOICE;
        var discount = blankToNull(draft.totalDiscountPercent()) != null
                ? TotalDiscount.percent(Math.toIntExact(scaled(draft.totalDiscountPercent(), 2)))
                : null;
        var input = new InvoiceInput(draft.customerId(), issueDate, from, to, draft.paymentDays(), draft.subject(),
                texts.defaultBody(scope, TextKind.HEADER).orElse(null),
                texts.defaultBody(scope, TextKind.FOOTER).orElse(null), discount, items, null,
                draft.customerReference(), null);
        var info = offer ? invoices.createOfferDraft(input) : invoices.createDraft(input);

        return new DraftResult(info.id(), type.name(), euros(info.netTotalCents()), euros(info.grossTotalCents()),
                iso(offer ? info.validUntil() : info.dueDate()), blankToNull(draft.totalDiscountPercent()),
                link(info, offer));
    }

    /**
     * Builds an item from a catalog entry or free text; given values replace the proposed ones.
     */
    private InvoiceItemInput item(DraftItem given, LocalDate day) {
        var catalogItemId = blankToNull(given.catalogItemId());

        if (catalogItemId == null && blankToNull(given.unitPrice()) == null) {
            throw new IllegalArgumentException("Price missing");
        }

        var base = invoices.proposeItem(catalogItemId, day);
        var description = blankToNull(given.description()) != null ? given.description().strip() : base.description();
        var quantity = blankToNull(given.quantity()) != null ? scaled(given.quantity(), 3) : base.quantityThousandths();
        var price = blankToNull(given.unitPrice()) != null ? scaled(given.unitPrice(), 2) : base.unitPriceCents();
        var discount = blankToNull(given.discountPercent()) != null
                ? Math.toIntExact(scaled(given.discountPercent(), 2))
                : 0;

        return new InvoiceItemInput(description, quantity, base.unitCode(), price, discount, base.taxCategory(),
                base.taxRateBp(), base.catalogItemId());
    }

    private String link(InvoiceInfo info, boolean offer) {
        return baseUrl.isEmpty() ? null : baseUrl + (offer ? "/offers/" : "/invoices/") + info.id();
    }

    private <A> A arguments(Map<String, Object> arguments, Class<A> type) {
        return mapper.convertValue(arguments == null ? Map.of() : arguments, type);
    }

    private void register(String name, String description, String schema,
            Function<Map<String, Object>, Object> handler) {
        register(name, description, schema, false, handler);
    }

    private void register(String name, String description, String schema, boolean writes,
            Function<Map<String, Object>, Object> handler) {
        tools.put(name, new Tool(new ToolInfo(name, description, mapper.readValue(schema, Map.class)), writes,
                handler));
    }

    private static <T, R> Page<R> page(List<T> rows, Function<T, R> mapping) {
        return new Page<>(rows.size(), rows.stream()
                .limit(LIMIT)
                .map(mapping)
                .toList());
    }

    private static ToolResult error(String text) {
        return new ToolResult(List.of(new Text(text)), true);
    }

    private static LocalDate date(String text) {
        return blankToNull(text) == null ? null : LocalDate.parse(text.strip());
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    /**
     * Turns a decimal text into a whole number of the smallest unit: {@code scale} decimal places, rounded half up.
     */
    private static long scaled(String text, int scale) {
        var value = text.strip();

        // bounded digits first: an exponent like 1E999999999 would make the arithmetic below run for ever
        if (!DECIMAL.matcher(value).matches()) {
            throw new IllegalArgumentException("Not a plain decimal number");
        }

        return new BigDecimal(value)
                .movePointRight(scale)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private static String euros(long cents) {
        return BigDecimal.valueOf(cents, 2).toPlainString();
    }

    /**
     * Name, description and input schema of a tool.
     */
    public record ToolInfo(String name, String description, Map<String, Object> inputSchema) {
    }

    /**
     * Result of a tool call in the shape MCP expects.
     */
    public record ToolResult(List<Text> content, boolean isError) {
    }

    /**
     * Text part of a tool result.
     */
    public record Text(String type, String text) {

        Text(String text) {
            this("text", text);
        }
    }

    private record Tool(ToolInfo info, boolean writes, Function<Map<String, Object>, Object> handler) {
    }

    record Query(String query) {
    }

    record Unpaid(Boolean onlyOverdue) {
    }

    record Id(String id) {
    }

    record Draft(String customerId, String subject, String customerReference, String serviceFrom, String serviceTo,
            Integer paymentDays, String totalDiscountPercent, List<DraftItem> items) {
    }

    record DraftItem(String catalogItemId, String description, String quantity, String unitPrice,
            String discountPercent) {
    }

    record Page<R>(int total, List<R> rows) {
    }

    record CustomerRow(String id, String number, String name, String zip, String city) {
    }

    record CatalogRow(String id, String number, String name, String unit, String unitPriceEur, String taxCategory) {
    }

    record InvoiceRow(String id, String number, String customer, String subject, String issueDate, String dueDate,
            String grossEur, String openEur, boolean overdue, long daysOverdue) {
    }

    record ItemRow(int position, String description, String quantity, String unit, String unitPriceEur,
            String discountPercent, String lineTotalEur) {
    }

    record InvoiceDetail(String id, String number, String type, String status, String customer, String issueDate,
            String serviceFrom, String serviceTo, String dueDate, String subject, List<ItemRow> items, String netEur,
            String taxEur, String grossEur) {
    }

    record DunningRow(String invoiceId, String invoiceNumber, String customer, String dueDate, long daysOverdue,
            String level, String claimEur) {
    }

    record OfferRow(String id, String number, String customer, String state, String validUntil, String grossEur) {
    }

    record DraftResult(String id, String type, String netEur, String grossEur, String dueDate, String totalDiscountPercent,
            String link) {
    }
}
