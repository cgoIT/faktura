package de.cgoit.faktura.dunning.internal;

import java.util.Set;
import java.util.stream.Collectors;

import de.cgoit.faktura.document.DunningHints;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.dunning.DunningSuggestion;
import org.springframework.stereotype.Component;

/**
 * Tells the invoice list which invoices have a dunning suggestion, so the document module needs no dependency on
 * dunning.
 */
@Component
public class InvoiceDunningHints implements DunningHints {

    private final DunningService dunnings;

    public InvoiceDunningHints(DunningService dunnings) {
        this.dunnings = dunnings;
    }

    @Override
    public Set<String> invoiceIdsWithSuggestion() {
        return dunnings.suggestions()
                .stream()
                .map(DunningSuggestion::invoiceId)
                .collect(Collectors.toSet());
    }
}
