package de.cgoit.faktura.home;

/**
 * A set-up step with its state.
 *
 * @param done {@code true} if the step is done: derived from the data, or ticked by the user for a manual step
 */
public record SetupItem(SetupStep step, boolean done) {
}
