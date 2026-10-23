package de.cgoit.faktura.security.internal;

/**
 * How people are identified ({@code faktura.security.mode}).
 */
public enum SecurityMode {

    /**
     * No sign-in: the reverse proxy in front protects the application. CSRF protection and headers still apply.
     */
    NONE,

    /**
     * Sign-in page with username and password.
     */
    FORM,

    /**
     * The username comes from a header set by a trusted reverse proxy.
     */
    PROXY_HEADER,

    /**
     * People are identified by the proxy header like in {@link #PROXY_HEADER}, but they never get the administrator
     * right that way. An administrator switches to "admin mode" by signing in on the form with a password; that
     * session wins over the header until the user leaves it.
     */
    PROXY_HEADER_ADMIN_FORM;

    /**
     * Tells whether the username may come from the proxy header.
     */
    public boolean usesHeader() {
        return this == PROXY_HEADER || this == PROXY_HEADER_ADMIN_FORM;
    }

    /**
     * Tells whether the sign-in form exists and accounts can have passwords.
     */
    public boolean usesForm() {
        return this == FORM || this == PROXY_HEADER_ADMIN_FORM;
    }
}
