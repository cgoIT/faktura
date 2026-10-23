package de.cgoit.faktura.recurring;

/**
 * State of a plan. Only an active plan generates drafts; an ended plan is finished for good.
 */
public enum RecurringStatus {
    ACTIVE, PAUSED, ENDED
}
