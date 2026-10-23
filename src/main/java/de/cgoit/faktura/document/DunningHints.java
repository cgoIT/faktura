package de.cgoit.faktura.document;

import java.util.Set;

/**
 * Implemented by the dunning module so that the invoice list can point at invoices that should be dunned without
 * the document module depending on dunning.
 */
public interface DunningHints {

    /**
     * Returns the ids of the invoices for which a dunning letter is suggested today.
     */
    Set<String> invoiceIdsWithSuggestion();
}
