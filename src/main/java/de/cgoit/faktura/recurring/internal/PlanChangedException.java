package de.cgoit.faktura.recurring.internal;

/**
 * Thrown inside the transaction of one period when the plan was edited after the job loaded it. The transaction rolls
 * back and the job picks the plan up again with its new settings on the next run.
 */
public class PlanChangedException extends RuntimeException {

    public PlanChangedException() {
        super("Recurring plan changed during the run");
    }
}
