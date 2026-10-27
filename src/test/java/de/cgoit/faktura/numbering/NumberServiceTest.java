package de.cgoit.faktura.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import de.cgoit.faktura.SqliteTest;
import de.cgoit.faktura.shared.RuleViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

@SqliteTest
class NumberServiceTest {

    @Autowired
    private NumberService numbers;

    @Autowired
    private NumberSettingsService settings;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void resetNumbering() {
        jdbc.sql("DELETE FROM number_sequence").update();
        jdbc.sql("UPDATE number_pattern SET pattern = 'RE{jahr}{#####}' WHERE scope = 'INVOICE'").update();
        jdbc.sql("UPDATE number_pattern SET pattern = 'KD{####}' WHERE scope = 'CUSTOMER'").update();
        jdbc.sql("UPDATE number_pattern SET pattern = 'ST{jahr}{#####}' WHERE scope = 'CANCELLATION'").update();
    }

    @Test
    void assignsConsecutiveNumbers() {
        var date = LocalDate.of(2027, 1, 5);

        var first = next(NumberScope.INVOICE, date);
        var second = next(NumberScope.INVOICE, date);

        assertThat(first).isEqualTo("RE202700001");
        assertThat(second).isEqualTo("RE202700002");
    }

    @Test
    void yearlyPatternRestartsInNewYear() {
        next(NumberScope.INVOICE, LocalDate.of(2027, 12, 31));
        next(NumberScope.INVOICE, LocalDate.of(2027, 12, 31));

        var number = next(NumberScope.INVOICE, LocalDate.of(2028, 1, 1));

        assertThat(number).isEqualTo("RE202800001");
    }

    @Test
    void patternWithoutYearNeverRestarts() {
        next(NumberScope.CUSTOMER, LocalDate.of(2027, 6, 1));

        var number = next(NumberScope.CUSTOMER, LocalDate.of(2028, 6, 1));

        assertThat(number).isEqualTo("KD0002");
    }

    @Test
    void scopesCountIndependently() {
        var date = LocalDate.of(2027, 3, 1);
        next(NumberScope.INVOICE, date);
        next(NumberScope.INVOICE, date);

        var offer = next(NumberScope.OFFER, date);

        assertThat(offer).isEqualTo("AN202700001");
    }

    @Test
    void rollbackKeepsNumberFree() {
        var date = LocalDate.of(2027, 2, 1);
        next(NumberScope.INVOICE, date);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            numbers.next(NumberScope.INVOICE, date);
            throw new IllegalStateException("pipeline failed");
        })).isInstanceOf(IllegalStateException.class);

        var number = next(NumberScope.INVOICE, date);

        assertThat(number).isEqualTo("RE202700002");
    }

    @Test
    void requiresSurroundingTransaction() {
        assertThatThrownBy(() -> numbers.next(NumberScope.INVOICE, LocalDate.of(2027, 1, 1)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void changedPatternAppliesToNextNumber() {
        var date = LocalDate.of(2027, 4, 1);
        next(NumberScope.INVOICE, date);

        settings.update(NumberScope.INVOICE, "R-{jahr}-{####}");

        assertThat(next(NumberScope.INVOICE, date)).isEqualTo("R-2027-0002");
    }

    @Test
    void switchingBetweenYearlyAndContinuousIsRejectedAfterFirstNumber() {
        next(NumberScope.INVOICE, LocalDate.of(2027, 4, 1));

        assertThatThrownBy(() -> settings.update(NumberScope.INVOICE, "RE{#####}"))
                .isInstanceOf(RuleViolationException.class);
    }

    @Test
    void switchingIsAllowedBeforeAnyNumberWasAssigned() {
        settings.update(NumberScope.INVOICE, "RE{#####}");

        assertThat(next(NumberScope.INVOICE, LocalDate.of(2027, 4, 1))).isEqualTo("RE00001");
    }

    @Test
    void documentScopesCannotShareTheSamePrefix() {
        assertThatThrownBy(() -> settings.update(NumberScope.CANCELLATION, "RE{jahr}{#####}"))
                .isInstanceOf(RuleViolationException.class);

        settings.update(NumberScope.CANCELLATION, "RE-{#####}");

        assertThat(next(NumberScope.CANCELLATION, LocalDate.of(2027, 1, 1))).isEqualTo("RE-00001");
    }

    @Test
    void listShowsNextNumberWithoutReservingIt() {
        var today = LocalDate.of(2027, 1, 10);
        next(NumberScope.INVOICE, today);

        var invoice = settings.list(today).stream()
                .filter(info -> info.scope() == NumberScope.INVOICE)
                .findFirst()
                .orElseThrow();

        assertThat(invoice.nextNumber()).isEqualTo("RE202700002");
        assertThat(next(NumberScope.INVOICE, today)).isEqualTo("RE202700002");
    }

    private String next(NumberScope scope, LocalDate date) {
        return transaction.execute(status -> numbers.next(scope, date));
    }

    @Test
    void shortYearPatternAndRaisedCounter() {
        jdbc.sql("UPDATE number_pattern SET pattern = 'RE0{jahr_kurz}{###}' WHERE scope = 'INVOICE'").update();
        var date = LocalDate.of(2027, 6, 1);

        settings.setNext(NumberScope.INVOICE, date, 42);

        assertThat(next(NumberScope.INVOICE, date)).isEqualTo("RE027042");
        assertThatThrownBy(() -> settings.setNext(NumberScope.INVOICE, date, 41))
                .isInstanceOf(RuleViolationException.class);
        assertThat(next(NumberScope.INVOICE, date)).isEqualTo("RE027043");
    }
}
