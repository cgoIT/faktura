package de.cgoit.faktura.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@SqliteTest
class CompanyServiceTest {

    @Autowired
    private CompanyService companies;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.sql("DELETE FROM tax_profile").update();
        jdbc.sql("DELETE FROM company").update();
    }

    @Test
    void emptyUntilFirstSave() {
        assertThat(companies.find()).isEmpty();
    }

    @Test
    void firstSaveCreatesCompany() {
        var saved = companies.save(company("Beispiel Webservice", 14));

        assertThat(saved.id()).isNotBlank();
        assertThat(companies.find()).contains(saved);
    }

    @Test
    void secondSaveChangesTheSameCompany() {
        var first = companies.save(company("Beispiel Webservice", 14));

        var second = companies.save(company("Beispiel Webservice GbR", 30));

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(companies.find().orElseThrow().paymentDays()).isEqualTo(30);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM company").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void blankOptionalValuesAreStoredAsEmptyAndIbanIsNormalized() {
        var input = new CompanyInfo(null, " Beispiel ", " ", "", null, null, "", null, null, null, null,
                "de02 1203 0000 0000 2020 51", "", null, 14);

        var saved = companies.save(input);

        assertThat(saved.name()).isEqualTo("Beispiel");
        assertThat(saved.owner()).isNull();
        assertThat(saved.country()).isEqualTo("DE");
        assertThat(saved.iban()).isEqualTo("DE02120300000000202051");
        assertThat(saved.bic()).isNull();
    }

    @Test
    void rejectsBlankName() {
        assertThatThrownBy(() -> companies.save(company(" ", 14)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIbanWithWrongChecksumOrLength() {
        var wrongChecksum = withIban("DE02 1203 0000 0000 2020 52");
        var tooLong = withIban("DE02" + "1".repeat(40));

        assertThatThrownBy(() -> companies.save(wrongChecksum)).isInstanceOf(RuleViolationException.class);
        assertThatThrownBy(() -> companies.save(tooLong)).isInstanceOf(RuleViolationException.class);
    }

    @Test
    void countryMustBeAKnownIsoCode() {
        var c = company("Beispiel", 14);
        var unknown = new CompanyInfo(null, c.name(), null, null, null, null, "XX", null, null, null, null, null,
                null, null, 14);
        var lowerCase = new CompanyInfo(null, c.name(), null, null, null, null, "at", null, null, null, null, null,
                null, null, 14);

        assertThatThrownBy(() -> companies.save(unknown))
                .isInstanceOf(RuleViolationException.class)
                .hasMessage("validation.countryUnknown");
        assertThat(companies.save(lowerCase).country()).isEqualTo("AT");
    }

    private static CompanyInfo withIban(String iban) {
        var c = company("Beispiel", 14);

        return new CompanyInfo(null, c.name(), null, null, null, null, "DE", null, null, null, null, iban, null,
                null, 14);
    }

    static CompanyInfo company(String name, int paymentDays) {
        return new CompanyInfo(null, name, "Erika Mustermann", "Musterweg 1", "12345", "Musterstadt", "DE",
                "info@example.test", null, "123/456/78901", null, null, null, null, paymentDays);
    }
}
