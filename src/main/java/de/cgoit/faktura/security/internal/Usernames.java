package de.cgoit.faktura.security.internal;

import java.util.Locale;

/**
 * The one way a username is written: without surrounding blanks, in lower case.
 */
public final class Usernames {

    private Usernames() {
    }

    public static String normalize(String username) {
        var stripped = username == null ? "" : username.strip();

        return stripped.toLowerCase(Locale.ROOT);
    }
}
