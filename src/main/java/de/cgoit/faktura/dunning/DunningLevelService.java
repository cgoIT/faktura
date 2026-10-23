package de.cgoit.faktura.dunning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.dunning.internal.DunningLevelRepository;
import de.cgoit.faktura.dunning.internal.DunningRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import de.cgoit.faktura.textblock.TextTemplateService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the configurable dunning levels: when each level is suggested, its fee and interest and its texts.
 * Levels are never deleted; an unused level is switched off. Created letters keep their own copy of level number
 * and name, so changing a level does not alter history.
 */
@Service
public class DunningLevelService {


    private final DunningLevelRepository levels;
    private final DunningRepository dunnings;
    private final TextTemplateService texts;
    private final InputValidator validator;

    public DunningLevelService(DunningLevelRepository levels, DunningRepository dunnings, TextTemplateService texts,
            InputValidator validator) {
        this.levels = levels;
        this.dunnings = dunnings;
        this.texts = texts;
        this.validator = validator;
    }

    /**
     * Lists all levels, mildest first.
     */
    @Transactional(readOnly = true)
    public List<DunningLevelInfo> list() {
        return levels.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<DunningLevelInfo> find(String id) {
        return levels.findById(id);
    }

    /**
     * Creates a level.
     *
     * @throws RuleViolationException if a value is out of range, a text uses an unknown placeholder, or the level
     *                                number is taken
     */
    @Transactional
    public DunningLevelInfo create(DunningLevelInput input) {
        validate(null, input);

        var id = UUID.randomUUID().toString();

        levels.insert(id, normalized(input));

        return levels.findById(id)
                .orElseThrow();
    }

    /**
     * Changes a level.
     *
     * The level number cannot change once letters exist for it, because the history refers to it.
     *
     * @throws RuleViolationException see {@link #create(DunningLevelInput)}
     */
    @Transactional
    public DunningLevelInfo update(String id, DunningLevelInput input) {
        var existing = levels.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown dunning level: " + id));

        if (existing.level() != input.level() && dunnings.existsForLevel(existing.level())) {
            throw new RuleViolationException("dunning.level.error.inUse");
        }

        validate(id, input);
        levels.update(id, normalized(input));

        return levels.findById(id)
                .orElseThrow();
    }

    private void validate(String id, DunningLevelInput input) {
        validator.check(input);

        var taken = levels.findByLevel(input.level())
                .filter(other -> !other.id().equals(id))
                .isPresent();

        if (taken) {
            throw new RuleViolationException("dunning.level.error.taken");
        }

        texts.validate(input.headerText());
        texts.validate(input.footerText());
    }

    private static DunningLevelInput normalized(DunningLevelInput input) {
        return new DunningLevelInput(input.level(), input.name().strip(), input.daysAfterDue(), input.feeCents(),
                input.interestBp(), input.newDueDays(), input.headerText(), input.footerText(), input.active());
    }
}
