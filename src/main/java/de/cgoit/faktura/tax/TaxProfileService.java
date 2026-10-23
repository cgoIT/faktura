package de.cgoit.faktura.tax;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.tax.internal.TaxCategoryReasonRepository;
import de.cgoit.faktura.tax.internal.TaxProfileRepository;
import de.cgoit.faktura.tax.internal.TaxProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves and maintains the tax profiles of the company. Calculation and output only read the resolved profile,
 * they never branch on the regime themselves.
 */
@Service
public class TaxProfileService {

    private static final long PREVIEW_NET_CENTS = 100_000;
    private static final long BASIS_POINTS = 10_000;
    private static final int MAX_REASON_LENGTH = 500;

    private final TaxProfileRepository profiles;
    private final CompanyService companies;
    private final TaxProperties properties;
    private final TaxCategoryReasonRepository reasons;

    public TaxProfileService(TaxProfileRepository profiles, CompanyService companies, TaxProperties properties,
            TaxCategoryReasonRepository reasons) {
        this.reasons = reasons;
        this.profiles = profiles;
        this.companies = companies;
        this.properties = properties;
    }

    /**
     * Returns the profile valid on the given day. The service date of a document decides which profile applies.
     *
     * @throws NoTaxProfileException if no profile is valid on that day
     */
    @Transactional(readOnly = true)
    public TaxProfileInfo resolve(LocalDate day) {
        return findValidOn(day).orElseThrow(() -> new NoTaxProfileException(day));
    }

    /**
     * Returns the profile valid on the given day, or empty if there is none (or no company yet).
     */
    @Transactional(readOnly = true)
    public Optional<TaxProfileInfo> findValidOn(LocalDate day) {
        return companies.find()
                .flatMap(company -> profiles.findValidOn(company.id(), day));
    }

    /**
     * Lists all profiles, newest first.
     */
    @Transactional(readOnly = true)
    public List<TaxProfileInfo> list() {
        return companies.find()
                .map(company -> profiles.findAll(company.id()))
                .orElse(List.of());
    }

    /**
     * Returns the configured defaults of a regime.
     */
    public TaxDefaults defaultsFor(TaxRegime regime) {
        return switch (regime) {
            case SMALL_BUSINESS -> properties.smallBusiness();
            case REGULAR -> properties.regular();
        };
    }

    /**
     * Tells whether items of the given EN 16931 category are taxed with the rate of the tax profile; other categories
     * (exempt, zero-rated, reverse charge, ...) carry 0 %.
     */
    public boolean carriesRate(String category) {
        return properties.ratedCategories() != null && properties.ratedCategories().contains(category);
    }

    /**
     * Returns the reason for every tax category that needs one (exempt, reverse charge, ...), in category order of the
     * configuration. The user's text wins over the configured default; a category whose text is blank is still listed
     * so that a missing reason is noticed when an e-invoice is made.
     */
    @Transactional(readOnly = true)
    public Map<String, String> categoryReasons() {
        var defaults = properties.categoryReasons() == null ? Map.<String, String>of() : properties.categoryReasons();
        var stored = reasons.findAll();
        var result = new LinkedHashMap<String, String>();

        defaults.forEach((category, text) ->
                result.put(category, stored.getOrDefault(category, text == null ? "" : text)));

        return result;
    }

    /**
     * Stores the reasons typed by the user. A blank text removes the user's text, so the configured default applies
     * again. Unknown categories are ignored.
     *
     * @throws RuleViolationException if a text is longer than 500 characters
     */
    @Transactional
    public void saveCategoryReasons(Map<String, String> texts) {
        var known = properties.categoryReasons() == null ? Set.<String>of() : properties.categoryReasons().keySet();

        for (var entry : texts.entrySet()) {
            var text = entry.getValue() == null ? "" : entry.getValue().strip();

            if (!known.contains(entry.getKey())) {
                continue;
            }

            if (text.length() > MAX_REASON_LENGTH) {
                throw new RuleViolationException("validation.tooLong");
            }

            if (text.isEmpty()) {
                reasons.delete(entry.getKey());
            } else {
                reasons.save(entry.getKey(), text);
            }
        }
    }

    /**
     * Shows what the defaults of a regime mean for a net amount of 1,000.00 EUR.
     */
    public TaxPreview preview(TaxRegime regime) {
        var defaults = defaultsFor(regime);
        var tax = (PREVIEW_NET_CENTS * defaults.rateBp() + BASIS_POINTS / 2) / BASIS_POINTS;

        return new TaxPreview(PREVIEW_NET_CENTS, tax, PREVIEW_NET_CENTS + tax, defaults);
    }

    /**
     * Starts a new profile with the defaults of its regime. The currently open-ended profile ends on the day before;
     * profiles that already ended are never changed.
     *
     * @throws RuleViolationException if the company is not set up or the start does not lie after all existing starts
     */
    @Transactional
    public TaxProfileInfo create(NewTaxProfile request) {
        var company = companies.find()
                .orElseThrow(() -> new RuleViolationException("tax.error.noCompany"));
        var latestStart = profiles.findAll(company.id()).stream()
                .map(TaxProfileInfo::validFrom)
                .max(LocalDate::compareTo);

        if (latestStart.isPresent() && !request.validFrom().isAfter(latestStart.get())) {
            throw new RuleViolationException("tax.error.startNotAfterLatest");
        }

        var defaults = defaultsFor(request.regime());
        var profile = new TaxProfileInfo(UUID.randomUUID().toString(), request.validFrom(), null, request.regime(),
                defaults.rateBp(), defaults.category(), defaults.exemptionText(), defaults.noticeText(),
                defaults.allowedCategories() == null ? List.of() : List.copyOf(defaults.allowedCategories()));

        profiles.closeOpenProfile(company.id(), request.validFrom().minusDays(1));
        profiles.insert(company.id(), profile);

        return profile;
    }
}
