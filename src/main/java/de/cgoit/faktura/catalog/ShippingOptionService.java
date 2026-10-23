package de.cgoit.faktura.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.cgoit.faktura.catalog.internal.ShippingOptionRepository;
import de.cgoit.faktura.shared.InputValidator;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the shipping options an invoice can choose from. Options are never deleted; one that is no longer wanted is
 * switched off. Documents keep their own copy, so changing an option does not alter history.
 */
@Service
public class ShippingOptionService {

    private final ShippingOptionRepository options;
    private final CatalogService catalog;
    private final InputValidator validator;

    public ShippingOptionService(ShippingOptionRepository options, CatalogService catalog, InputValidator validator) {
        this.options = options;
        this.catalog = catalog;
        this.validator = validator;
    }

    /**
     * Lists all options in the order of the users' sorting.
     */
    @Transactional(readOnly = true)
    public List<ShippingOptionInfo> list() {
        return options.findAll(false);
    }

    /**
     * Lists the options that are switched on, for the choice on a document.
     */
    @Transactional(readOnly = true)
    public List<ShippingOptionInfo> listActive() {
        return options.findAll(true);
    }

    @Transactional(readOnly = true)
    public Optional<ShippingOptionInfo> find(String id) {
        return options.findById(id);
    }

    /**
     * Creates an option.
     *
     * @throws RuleViolationException if a value is out of range or the tax category is unknown
     */
    @Transactional
    public ShippingOptionInfo create(ShippingOptionInput input) {
        var valid = validated(input);
        var id = UUID.randomUUID().toString();

        options.insert(id, valid);

        return options.findById(id)
                .orElseThrow();
    }

    /**
     * Changes an option; documents that copied it are not touched.
     *
     * @throws RuleViolationException see {@link #create(ShippingOptionInput)}
     */
    @Transactional
    public ShippingOptionInfo update(String id, ShippingOptionInput input) {
        options.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown shipping option: " + id));
        options.update(id, validated(input));

        return options.findById(id)
                .orElseThrow();
    }

    private ShippingOptionInput validated(ShippingOptionInput input) {
        validator.check(input);

        var category = input.taxCategory() == null || input.taxCategory().isBlank() ? null : input.taxCategory();

        if (category != null && !catalog.taxCategories().contains(category)) {
            throw new RuleViolationException("catalog.error.taxCategory", "taxCategory");
        }

        return new ShippingOptionInput(input.name().strip(), input.amountCents(), category,
                category == null ? 0 : input.taxRateBp(), input.sortOrder(), input.active());
    }
}
