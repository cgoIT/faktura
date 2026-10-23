package de.cgoit.faktura.recurring;

/**
 * State of one generated period: the draft waits for approval, was finalized, or the period was not billed.
 */
public enum RunStatus {
    DRAFT, RELEASED, SKIPPED
}
