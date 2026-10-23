package de.cgoit.faktura.recurring.internal;

import de.cgoit.faktura.document.DraftDeleted;
import de.cgoit.faktura.document.InvoiceFinalized;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the status of a run consistent with its invoice: a deleted draft makes the run skipped, a finalized invoice
 * makes it released. Runs in the transaction of the publisher, so a rollback undoes both.
 */
@Component
public class RunStatusListener {

    private final RunRepository runs;

    public RunStatusListener(RunRepository runs) {
        this.runs = runs;
    }

    @EventListener
    void onDraftDeleted(DraftDeleted event) {
        runs.markSkippedByDocument(event.documentId());
    }

    @EventListener
    void onInvoiceFinalized(InvoiceFinalized event) {
        runs.markReleasedByDocument(event.documentId());
    }
}
