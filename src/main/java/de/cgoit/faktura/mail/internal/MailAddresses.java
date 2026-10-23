package de.cgoit.faktura.mail.internal;

import java.util.ArrayList;
import java.util.List;

import de.cgoit.faktura.shared.RuleViolationException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Parses and checks the recipient addresses typed into the send dialog.
 */
public final class MailAddresses {

    private MailAddresses() {
    }

    /**
     * Splits addresses separated by comma or semicolon. Only plain addresses are accepted, no display names, so a
     * typed name cannot add further recipients or headers.
     *
     * @throws RuleViolationException if an address is invalid, there is none, or there are more than {@code max}
     */
    public static List<InternetAddress> parse(String raw, int max) {
        var addresses = new ArrayList<InternetAddress>();

        try {
            for (var address : InternetAddress.parse(raw.replace(';', ','), true)) {
                address.validate();

                if (address.getPersonal() != null || !address.getAddress().contains("@")) {
                    throw new RuleViolationException("mail.error.recipients", "recipients");
                }

                if (addresses.stream().noneMatch(known -> known.getAddress().equalsIgnoreCase(address.getAddress()))) {
                    addresses.add(new InternetAddress(address.getAddress()));
                }
            }
        } catch (AddressException e) {
            throw new RuleViolationException("mail.error.recipients", "recipients");
        }

        if (addresses.isEmpty()) {
            throw new RuleViolationException("mail.error.recipients", "recipients");
        }

        if (addresses.size() > max) {
            throw new RuleViolationException("mail.error.recipientCount", "recipients");
        }

        return addresses;
    }

    /**
     * Parses configured addresses leniently; an invalid configured address is ignored.
     */
    public static List<InternetAddress> parseConfigured(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        try {
            return List.of(InternetAddress.parse(raw.replace(';', ','), true));
        } catch (AddressException e) {
            return List.of();
        }
    }
}
