package de.cgoit.faktura.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.company.CompanyInfo;
import de.cgoit.faktura.company.CompanyService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class TaxProfileServiceTest {

    @Autowired
    private TaxProfileService profiles;

    @Autowired
    private CompanyService companies;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void categoryReasonsStartWithConfiguredDefaultsAndCanBeOverridden() {
        assertThat(profiles.categoryReasons()).containsKeys("E", "AE", "K", "G", "O");
        assertThat(profiles.categoryReasons().get("AE")).contains("§ 13b UStG");

        profiles.saveCategoryReasons(Map.of("AE", "  Reverse Charge  ", "X", "ignored"));

        assertThat(profiles.categoryReasons().get("AE")).isEqualTo("Reverse Charge");
        assertThat(profiles.categoryReasons()).doesNotContainKey("X");

        profiles.saveCategoryReasons(Map.of("AE", " "));

        assertThat(profiles.categoryReasons().get("AE")).contains("§ 13b UStG");
    }

    @Test
    void tooLongCategoryReasonIsRejected() {
        var tooLong = Map.of("E", "x".repeat(501));

        assertThatThrownBy(() -> profiles.saveCategoryReasons(tooLong)).isInstanceOf(RuleViolationException.class);
    }

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM tax_category_reason").update();
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
    }

    @Test
    void createWithoutCompanyIsRejected() {
        var request = new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> profiles.create(request)).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void smallBusinessProfileTakesConfiguredDefaults() {
        setUpCompany();

        var profile = profiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1)));

        assertThat(profile.defaultRateBp()).isZero();
        assertThat(profile.defaultCategory()).isEqualTo("E");
        assertThat(profile.exemptionText()).contains("§ 19 UStG");
        assertThat(profile.validTo()).isNull();
    }

    @Test
    void regularProfileTakesConfiguredDefaults() {
        setUpCompany();

        var profile = profiles.create(new NewTaxProfile(TaxRegime.REGULAR, LocalDate.of(2026, 1, 1)));

        assertThat(profile.defaultRateBp()).isEqualTo(1900);
        assertThat(profile.defaultCategory()).isEqualTo("S");
    }

    @Test
    void newProfileEndsPredecessorOnThePreviousDay() {
        setUpCompany();
        profiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1)));

        profiles.create(new NewTaxProfile(TaxRegime.REGULAR, LocalDate.of(2028, 1, 1)));

        var list = profiles.list();
        assertThat(list).hasSize(2);
        assertThat(list.get(0).regime()).isEqualTo(TaxRegime.REGULAR);
        assertThat(list.get(0).validTo()).isNull();
        assertThat(list.get(1).validTo()).isEqualTo(LocalDate.of(2027, 12, 31));
    }

    @Test
    void resolveUsesTheProfileOfTheServiceDateIncludingBoundaries() {
        setUpCompany();
        profiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1)));
        profiles.create(new NewTaxProfile(TaxRegime.REGULAR, LocalDate.of(2028, 1, 1)));

        assertThat(profiles.resolve(LocalDate.of(2026, 1, 1)).regime()).isEqualTo(TaxRegime.SMALL_BUSINESS);
        assertThat(profiles.resolve(LocalDate.of(2027, 12, 31)).regime()).isEqualTo(TaxRegime.SMALL_BUSINESS);
        assertThat(profiles.resolve(LocalDate.of(2028, 1, 1)).regime()).isEqualTo(TaxRegime.REGULAR);
        assertThat(profiles.resolve(LocalDate.of(2040, 6, 1)).regime()).isEqualTo(TaxRegime.REGULAR);
    }

    @Test
    void resolveBeforeFirstProfileFails() {
        setUpCompany();
        profiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1)));

        assertThatThrownBy(() -> profiles.resolve(LocalDate.of(2025, 12, 31)))
                .isInstanceOf(NoTaxProfileException.class);
    }

    @Test
    void startNotAfterLatestStartIsRejected() {
        setUpCompany();
        profiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1)));

        var sameDay = new NewTaxProfile(TaxRegime.REGULAR, LocalDate.of(2026, 1, 1));
        var earlier = new NewTaxProfile(TaxRegime.REGULAR, LocalDate.of(2025, 1, 1));

        assertThatThrownBy(() -> profiles.create(sameDay)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> profiles.create(earlier)).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void previewRoundsTaxHalfUpFromRate() {
        var small = profiles.preview(TaxRegime.SMALL_BUSINESS);
        var regular = profiles.preview(TaxRegime.REGULAR);

        assertThat(small.taxCents()).isZero();
        assertThat(small.grossCents()).isEqualTo(100_000);
        assertThat(regular.taxCents()).isEqualTo(19_000);
        assertThat(regular.grossCents()).isEqualTo(119_000);
    }

    private void setUpCompany() {
        companies.save(new CompanyInfo(null, "Beispiel Webservice", null, null, null, null, "DE", null, null, null,
                null, null, null, null, 14));
    }

    @Test
    void newProfilesCarryTheAllowedCategoriesOfTheirRegime() {
        setUpCompany();

        var small = profiles.create(new NewTaxProfile(TaxRegime.SMALL_BUSINESS, LocalDate.of(2026, 1, 1)));
        var regular = profiles.create(new NewTaxProfile(TaxRegime.REGULAR, LocalDate.of(2028, 1, 1)));

        assertThat(small.allowedCategories()).containsExactly("E");
        assertThat(small.allows("E")).isTrue();
        assertThat(small.allows("S")).isFalse();
        assertThat(regular.allowedCategories()).isEmpty();
        assertThat(regular.allows("S")).isTrue();
        assertThat(profiles.resolve(LocalDate.of(2026, 6, 1)).allowedCategories()).containsExactly("E");
    }
}
