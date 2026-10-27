package de.cgoit.faktura.numbering;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.cgoit.faktura.numbering.internal.NumberSequenceRepository;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and changes the configured number patterns.
 */
@Service
public class NumberSettingsService {

    private static final Set<NumberScope> DOCUMENT_SCOPES = EnumSet.of(
            NumberScope.INVOICE, NumberScope.OFFER, NumberScope.CANCELLATION, NumberScope.DUNNING);

    private final NumberSequenceRepository sequences;
    private final NumberService numbers;

    public NumberSettingsService(NumberSequenceRepository sequences, NumberService numbers) {
        this.sequences = sequences;
        this.numbers = numbers;
    }

    /**
     * Lists the pattern of every scope with an example of the next number for the period of {@code today}.
     */
    @Transactional(readOnly = true)
    public List<NumberPatternInfo> list(LocalDate today) {
        return Arrays.stream(NumberScope.values())
                .map(scope -> describe(scope, today))
                .toList();
    }

    /**
     * Changes the pattern of a scope. Once numbers were assigned, the pattern may no longer switch between yearly
     * restart and continuous counting, because numbers could repeat.
     *
     * @throws IllegalArgumentException if the pattern is invalid
     * @throws RuleViolationException if the switch between yearly and continuous counting is not allowed
     */
    @Transactional
    public void update(NumberScope scope, String patternText) {
        var pattern = NumberPattern.parse(patternText.strip());
        var current = numbers.parsedPattern(scope);

        if (pattern.resetsYearly() != current.resetsYearly() && sequences.hasAssignedNumbers(scope)) {
            throw new RuleViolationException("numbering.error.yearlyLocked");
        }

        requireDistinctPrefix(scope, pattern);
        sequences.updatePattern(scope, pattern.toString());
    }

    /**
     * Changes pattern and counter together; either both apply or neither.
     */
    @Transactional
    public void update(NumberScope scope, String patternText, LocalDate date, long next) {
        update(scope, patternText);
        setNext(scope, date, next);
    }

    /**
     * Sets the counter so that {@code next} is assigned next in the period of {@code date}, e.g. to continue
     * the numbers of a previous system. The counter can only move forward: lowering it could repeat a number.
     *
     * @throws RuleViolationException if {@code next} is below 1 or not above the last assigned counter
     */
    @Transactional
    public void setNext(NumberScope scope, LocalDate date, long next) {
        var period = NumberService.period(numbers.parsedPattern(scope), date);

        if (next < 1 || !sequences.raiseCounter(scope, period, next - 1)) {
            throw new RuleViolationException("numbering.error.counterLow");
        }
    }

    /**
     * Numbered documents share one unique number column, so their patterns must start with different literals;
     * otherwise two scopes would eventually assign the same number.
     */
    private void requireDistinctPrefix(NumberScope scope, NumberPattern pattern) {
        if (!DOCUMENT_SCOPES.contains(scope)) {
            return;
        }

        var prefix = prefixOf(pattern);
        var clash = DOCUMENT_SCOPES.stream()
                .filter(other -> other != scope)
                .anyMatch(other -> prefixOf(numbers.parsedPattern(other)).equals(prefix));

        if (clash) {
            throw new RuleViolationException("numbering.error.prefixTaken");
        }
    }

    private static String prefixOf(NumberPattern pattern) {
        var text = pattern.toString();

        return text.substring(0, text.indexOf('{'));
    }

    private NumberPatternInfo describe(NumberScope scope, LocalDate today) {
        var pattern = numbers.parsedPattern(scope);
        var last = sequences.lastValue(scope, NumberService.period(pattern, today));

        return new NumberPatternInfo(scope, pattern.toString(), pattern.format(today.getYear(), last + 1), last + 1);
    }
}
