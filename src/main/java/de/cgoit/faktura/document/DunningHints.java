package de.cgoit.faktura.document;

import java.util.Optional;
import java.util.Set;

/**
 * Implemented by the dunning module so that the invoice pages can show dunning information without the document
 * module depending on dunning.
 */
public interface DunningHints {

    /**
     * Returns the ids of the invoices for which a dunning letter is suggested today.
     */
    Set<String> invoiceIdsWithSuggestion();

    /**
     * Returns the costs claimed by the most recent letter of an invoice. Costs of earlier letters are not added up.
     * Empty without a letter or if the most recent one claims no costs.
     */
    Optional<DunningClaim> latestClaim(String invoiceId);
}
