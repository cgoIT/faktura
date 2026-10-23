package de.cgoit.faktura.dunning;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Values of a dunning level to save; see {@link DunningLevelInfo} for the meaning. The annotations are the rules for
 * the values; the service checks them.
 */
public record DunningLevelInput(
        @Min(value = 1, message = "{dunning.level.error.level}") @Max(value = 99, message = "{dunning.level.error.level}")
        int level,
        @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String name,
        @Min(value = 0, message = "{dunning.level.error.days}") @Max(value = 3650, message = "{dunning.level.error.days}")
        int daysAfterDue,
        @Min(value = 0, message = "{validation.money}") @Max(value = 99_999_999L, message = "{validation.money}")
        long feeCents,
        @Min(value = 0, message = "{validation.percent}") @Max(value = 10_000, message = "{validation.percent}")
        int interestBp,
        @Min(value = 0, message = "{dunning.level.error.days}") @Max(value = 3650, message = "{dunning.level.error.days}")
        int newDueDays,
        @NotBlank(message = "{validation.required}") @Size(max = 4000, message = "{validation.tooLong}")
        String headerText,
        @NotBlank(message = "{validation.required}") @Size(max = 4000, message = "{validation.tooLong}")
        String footerText,
        boolean active) {
}
