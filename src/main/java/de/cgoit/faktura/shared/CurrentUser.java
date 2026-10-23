package de.cgoit.faktura.shared;

/**
 * The person on whose behalf the current code runs. The audit log and new drafts record this name. Outside a web
 * request (scheduled jobs, the dev seeder, tests) it is {@link #SYSTEM}.
 */
public interface CurrentUser {

    /**
     * Actor name for work that no person triggered.
     */
    String SYSTEM = "system";

    /**
     * Returns the username of the signed-in person, or {@link #SYSTEM} if there is none.
     */
    String username();
}
