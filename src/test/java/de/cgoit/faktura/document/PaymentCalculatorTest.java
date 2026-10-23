package de.cgoit.faktura.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentCalculatorTest {

    private static final LocalDate DUE = LocalDate.of(2026, 11, 3);

    @ParameterizedTest(name = "paid {0} of 10000 on {1}: {2}, overdue={3}, days={4}, open={5}")
    @CsvSource({
            "0,     2026-11-02, OPEN,           false, 0,  10000",
            "0,     2026-11-03, OPEN,           false, 0,  10000",
            "0,     2026-11-04, OPEN,           true,  1,  10000",
            "0,     2026-12-03, OPEN,           true,  30, 10000",
            "4000,  2026-11-03, PARTIALLY_PAID, false, 0,  6000",
            "4000,  2026-11-04, PARTIALLY_PAID, true,  1,  6000",
            "10000, 2026-12-03, PAID,           false, 0,  0",
            "12500, 2026-12-03, PAID,           false, 0,  0"
    })
    void derivesStateAndOverdue(long paid, LocalDate today, PaymentState state, boolean overdue, long days, long open) {
        var balance = PaymentCalculator.balance(10_000, paid, DUE, false, today);

        assertThat(balance.state()).isEqualTo(state);
        assertThat(balance.overdue()).isEqualTo(overdue);
        assertThat(balance.daysOverdue()).isEqualTo(days);
        assertThat(balance.openCents()).isEqualTo(open);
    }

    @Test
    void overpaymentIsShownNotBlocked() {
        var balance = PaymentCalculator.balance(10_000, 12_500, DUE, false, DUE);

        assertThat(balance.overpaidCents()).isEqualTo(2_500);
        assertThat(balance.openCents()).isZero();
    }

    @Test
    void cancelledInvoiceIsNeverOverdue() {
        var balance = PaymentCalculator.balance(10_000, 0, DUE, true, DUE.plusDays(90));

        assertThat(balance.state()).isEqualTo(PaymentState.CANCELLED);
        assertThat(balance.overdue()).isFalse();
    }

    @Test
    void reversedPaymentOpensTheInvoiceAgain() {
        var balance = PaymentCalculator.balance(10_000, 10_000 - 10_000, DUE, false, DUE.plusDays(5));

        assertThat(balance.state()).isEqualTo(PaymentState.OPEN);
        assertThat(balance.overdue()).isTrue();
    }

    @Test
    void invoiceWithoutDueDateIsNeverOverdue() {
        assertThat(PaymentCalculator.balance(10_000, 0, null, false, DUE).overdue()).isFalse();
    }

    @Test
    void zeroTotalCountsAsPaid() {
        assertThat(PaymentCalculator.balance(0, 0, DUE, false, DUE.plusDays(3)).state()).isEqualTo(PaymentState.PAID);
    }
}
