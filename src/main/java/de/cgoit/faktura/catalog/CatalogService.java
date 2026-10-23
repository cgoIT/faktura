package de.cgoit.faktura.catalog;

import java.text.Collator;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.catalog.internal.CatalogItemRepository;
import de.cgoit.faktura.numbering.NumberScope;
import de.cgoit.faktura.numbering.NumberService;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the service catalog. Entries are never deleted, only deactivated, so that they stay traceable.
 */
@Service
public class CatalogService {

    /** EN 16931 (UNTDID 5305) tax category codes; the codes are a standard, the rates stay in the tax profile. */
    private static final List<String> TAX_CATEGORIES = List.of("S", "Z", "E", "AE", "K", "G", "O", "L", "M");

    private final CatalogItemRepository items;
    private final NumberService numbers;
    private final InputValidator validator;
    private final Clock clock;

    public CatalogService(CatalogItemRepository items, NumberService numbers, InputValidator validator, Clock clock) {
        this.items = items;
        this.numbers = numbers;
        this.validator = validator;
        this.clock = clock;
    }

    /**
     * Lists entries whose number, name or description contains the search text (case-insensitive, German rules),
     * ordered by number. With a search text, entries that match in the name come first.
     *
     * @param query      search text, blank for all
     * @param activeOnly {@code true} to hide deactivated entries
     */
    @Transactional(readOnly = true)
    public List<CatalogItemInfo> search(String query, boolean activeOnly) {
        return search(query, activeOnly, CatalogOrder.NUMBER);
    }

    /**
     * Like {@link #search(String, boolean)}, with the order of entries of equal relevance chosen by the caller.
     * Alphabetical order follows German rules (umlauts sort with their base letter).
     */
    @Transactional(readOnly = true)
    public List<CatalogItemInfo> search(String query, boolean activeOnly, CatalogOrder order) {
        var needle = query == null ? "" : query.strip().toLowerCase(Locale.GERMAN);
        var byRelevance = Comparator.comparingInt((CatalogItemInfo item) -> relevance(item, needle));
        var collator = Collator.getInstance(Locale.GERMAN);
        var comparator = order == CatalogOrder.NAME
                ? byRelevance.thenComparing(CatalogItemInfo::name, collator)
                : byRelevance;

        return items.findAll(activeOnly).stream()
                .filter(item -> needle.isEmpty()
                        || contains(item.number(), needle)
                        || contains(item.name(), needle)
                        || contains(item.description(), needle))
                .sorted(comparator)
                .toList();
    }

    /**
     * Returns the selectable EN 16931 tax category codes in display order.
     */
    public List<String> taxCategories() {
        return TAX_CATEGORIES;
    }

    /**
     * Lists the entries that can be chosen for new documents.
     */
    @Transactional(readOnly = true)
    public List<CatalogItemInfo> listActive() {
        return items.findAll(true);
    }

    @Transactional(readOnly = true)
    public Optional<CatalogItemInfo> find(String id) {
        return items.findById(id);
    }

    /**
     * Creates an entry and assigns the next catalog number in the same transaction, so a failed save leaves the
     * number free.
     *
     * @throws RuleViolationException if the input is invalid
     */
    @Transactional
    public CatalogItemInfo create(CatalogItemInput input) {
        var valid = validated(input);
        var id = UUID.randomUUID().toString();
        var number = numbers.next(NumberScope.CATALOG, LocalDate.now(clock));
        items.insert(id, number, valid.name(), valid.description(), valid.unitId(), valid.unitPriceCents(),
                valid.taxCategory());

        return items.findById(id).orElseThrow();
    }

    /**
     * Changes an entry. Documents already created keep their own copy of the values.
     *
     * @throws RuleViolationException if the entry does not exist or the input is invalid
     */
    @Transactional
    public CatalogItemInfo update(String id, CatalogItemInput input) {
        requireExisting(id);

        var valid = validated(input);
        items.update(id, valid.name(), valid.description(), valid.unitId(), valid.unitPriceCents(),
                valid.taxCategory());

        return items.findById(id).orElseThrow();
    }

    /**
     * Hides the entry from the choice for new documents.
     */
    @Transactional
    public void deactivate(String id) {
        requireExisting(id);
        items.setActive(id, false);
    }

    @Transactional
    public void activate(String id) {
        requireExisting(id);
        items.setActive(id, true);
    }

    private void requireExisting(String id) {
        items.findById(id)
                .orElseThrow(() -> new RuleViolationException("catalog.error.unknown"));
    }

    /**
     * Normalizes the input and checks it against the annotations of {@link CatalogItemInput}; the rules that depend
     * on other data (the unit must exist, the tax category must be a known code) follow.
     */
    private CatalogItemInput validated(CatalogItemInput input) {
        var category = blankToNull(input.taxCategory());
        var normalized = new CatalogItemInput(blankToNull(input.name()), blankToNull(input.description()),
                input.unitId(), input.unitPriceCents(), category == null ? null : category.toUpperCase(Locale.ROOT));

        validator.check(normalized);

        if (normalized.unitId() == null || !items.unitExists(normalized.unitId())) {
            throw new RuleViolationException("catalog.error.unit", "unitId");
        }

        if (normalized.taxCategory() != null && !TAX_CATEGORIES.contains(normalized.taxCategory())) {
            throw new RuleViolationException("catalog.error.taxCategory", "taxCategory");
        }

        return normalized;
    }

    /**
     * Ranks a match: entries whose name starts with the search text first, then those that contain it in the name,
     * then those that match the number or only the description. The sort is stable, so equal ranks keep the order of
     * the number.
     */
    private static int relevance(CatalogItemInfo item, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }

        var name = item.name().toLowerCase(Locale.GERMAN);

        if (name.startsWith(needle)) {
            return 0;
        }

        return name.contains(needle) ? 1 : 2;
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.GERMAN).contains(needle);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
