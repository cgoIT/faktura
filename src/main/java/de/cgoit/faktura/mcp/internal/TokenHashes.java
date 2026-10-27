package de.cgoit.faktura.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 of tokens. A fast hash is enough because a token is 256 random bits, not a password a person chose.
 */
public final class TokenHashes {

    private TokenHashes() {
    }

    public static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }
}
