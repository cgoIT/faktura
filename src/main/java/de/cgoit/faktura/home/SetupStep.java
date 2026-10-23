package de.cgoit.faktura.home;

/**
 * A step of the initial set-up. Derived steps follow from the data and tick themselves; manual steps are ticked by the
 * user, because only the user can judge whether the settings fit.
 */
public enum SetupStep {
    COMPANY(false, "/company"),
    TAX_PROFILE(false, "/company/tax-profiles"),
    NUMBERING(true, "/numbering"),
    UNITS(true, "/units"),
    DUNNING_LEVELS(true, "/dunning/levels"),
    CATALOG(false, "/catalog"),
    CUSTOMERS(false, "/customers"),
    INVOICE(false, "/invoices");

    private final boolean manual;
    private final String path;

    SetupStep(boolean manual, String path) {
        this.manual = manual;
        this.path = path;
    }

    /**
     * Tells whether the user ticks this step off by hand.
     */
    public boolean manual() {
        return manual;
    }

    /**
     * Page where the step is done.
     */
    public String path() {
        return path;
    }
}
