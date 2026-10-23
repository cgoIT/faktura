package de.cgoit.faktura.security;

/**
 * A person who may use the application. Never contains the password hash.
 *
 * @param username   unique sign-in name in lower case, also the name the audit log shows
 * @param admin      may manage users
 * @param active     inactive users cannot sign in; they are never deleted because the audit log names them
 * @param lastLogin  ISO timestamp of the last sign-in, {@code null} if there was none
 * @param passwordSet {@code true} for an account that signs in with a password; others exist only through a proxy
 */
public record UserInfo(String id, String username, String displayName, boolean admin, boolean active,
        String lastLogin,
        boolean passwordSet) {

    /**
     * Shows only the technical id: the names are personal data.
     */
    @Override
    public String toString() {
        return "UserInfo[id=" + id + "]";
    }
}
