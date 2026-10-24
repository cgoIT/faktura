package de.cgoit.faktura.catalog;

import static de.cgoit.faktura.shared.Texts.blankToNull;

import java.util.List;
import java.util.UUID;

import de.cgoit.faktura.catalog.internal.UnitRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the units of measure.
 */
@Service
public class UnitService {


    private final UnitRepository units;
    private final InputValidator validator;

    public UnitService(UnitRepository units, InputValidator validator) {
        this.units = units;
        this.validator = validator;
    }

    @Transactional(readOnly = true)
    public List<UnitInfo> list() {
        return units.findAll();
    }

    /**
     * Creates a unit.
     *
     * @throws RuleViolationException if the code is already used
     */
    @Transactional
    public UnitInfo create(String code, String name, String namePlural, String uneceCode) {
        var unit = normalized(UUID.randomUUID().toString(), code, name, namePlural, uneceCode);
        requireUniqueCode(unit);
        units.insert(unit);

        return unit;
    }

    /**
     * Changes a unit. Existing documents keep their own copy of the unit.
     *
     * @throws RuleViolationException if the code belongs to another unit
     */
    @Transactional
    public UnitInfo update(String id, String code, String name, String namePlural, String uneceCode) {
        units.findById(id)
                .orElseThrow(() -> new RuleViolationException("unit.error.unknown"));

        var unit = normalized(id, code, name, namePlural, uneceCode);
        requireUniqueCode(unit);
        units.update(unit);

        return unit;
    }

    /**
     * Deletes a unit that no catalog item uses.
     *
     * @throws RuleViolationException if the unit is in use
     */
    @Transactional
    public void delete(String id) {
        if (units.isUsed(id)) {
            throw new RuleViolationException("unit.error.inUse");
        }

        units.delete(id);
    }

    /**
     * The values of a unit as the service checks them; the annotations are the rules.
     */
    private record UnitValues(
            @NotBlank(message = "{validation.required}") @Size(max = 20, message = "{validation.tooLong}") String code,
            @NotBlank(message = "{validation.required}") @Size(max = 100, message = "{validation.tooLong}") String name,
            @Size(max = 100, message = "{validation.tooLong}") String namePlural,
            @NotBlank(message = "{validation.required}")
            @Pattern(regexp = "[A-Z0-9]{2,3}", message = "{unit.error.uneceCode}") String uneceCode) {
    }

    private UnitInfo normalized(String id, String code, String name, String namePlural, String uneceCode) {
        var unece = blankToNull(uneceCode);
        var values = new UnitValues(blankToNull(code), blankToNull(name), blankToNull(namePlural),
                unece == null ? null : unece.toUpperCase());

        validator.check(values);

        return new UnitInfo(id, values.code(), values.name(), values.namePlural(), values.uneceCode());
    }

    private void requireUniqueCode(UnitInfo unit) {
        if (units.existsByCode(unit.code(), unit.id())) {
            throw new RuleViolationException("unit.error.codeExists");
        }
    }
}
