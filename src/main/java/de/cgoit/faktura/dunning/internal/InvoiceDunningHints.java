package de.cgoit.faktura.dunning.internal;

import java.util.Comparator;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import de.cgoit.faktura.document.DunningClaim;
import de.cgoit.faktura.document.DunningHints;
import de.cgoit.faktura.dunning.DunningInfo;
import de.cgoit.faktura.dunning.DunningService;
import de.cgoit.faktura.dunning.DunningSuggestion;
import org.springframework.stereotype.Component;

/**
 * Tells the invoice pages about dunning suggestions and claimed costs, so the document module needs no dependency
 * on dunning.
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

    @Override
    public Optional<DunningClaim> latestClaim(String invoiceId) {
        return dunnings.listFor(invoiceId)
                .stream()
                .max(Comparator.comparing(DunningInfo::date)
                        .thenComparingInt(DunningInfo::level))
                .map(dunning -> new DunningClaim(dunning.number(), dunning.feeCents(), dunning.interestCents()))
                .filter(claim -> claim.costCents() > 0);
    }
}
