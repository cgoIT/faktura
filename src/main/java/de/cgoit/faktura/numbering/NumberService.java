package de.cgoit.faktura.numbering;

import java.time.LocalDate;

import de.cgoit.faktura.numbering.internal.NumberSequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assigns gap-free numbers per scope. Patterns with {@code {jahr}} restart the counter every year.
 */
@Service
public class NumberService {

    private static final String NO_PERIOD = "0";

    private final NumberSequenceRepository sequences;

    public NumberService(NumberSequenceRepository sequences) {
        this.sequences = sequences;
    }

    /**
     * Assigns the next number of the scope. It must be called in the transaction that stores the numbered object:
     * if that transaction rolls back, the counter is rolled back too and the number stays free.
     *
     * @param date date whose year is used for the {@code {jahr}} placeholder and the counter period
     * @throws IllegalTransactionStateException if no transaction is active
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String next(NumberScope scope, LocalDate date) {
        var pattern = parsedPattern(scope);
        var counter = sequences.increment(scope, period(pattern, date));

        return pattern.format(date.getYear(), counter);
    }

    NumberPattern parsedPattern(NumberScope scope) {
        var text = sequences.findPattern(scope)
                .orElseThrow(() -> new IllegalStateException("No number pattern configured for " + scope));

        return NumberPattern.parse(text);
    }

    static String period(NumberPattern pattern, LocalDate date) {
        return pattern.resetsYearly() ? String.valueOf(date.getYear()) : NO_PERIOD;
    }
}
