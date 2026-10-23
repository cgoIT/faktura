package de.cgoit.faktura.document;

import java.util.List;

import de.cgoit.faktura.catalog.CatalogItemInfo;

/**
 * Catalog entries that may be added to an invoice for a search text. Only the first entries are listed.
 *
 * @param items   the entries to show, at most the requested number
 * @param matches how many entries match in total (not only the listed ones)
 * @param hidden  how many active entries match the search but are left out because the tax profile does not allow
 *                their tax category
 */
public record CatalogChoices(List<CatalogItemInfo> items, int matches, int hidden) {

    /**
     * Number of matching entries that are not listed.
     */
    public int more() {
        return matches - items.size();
    }
}
