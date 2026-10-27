package de.cgoit.faktura.mcp;

import java.time.LocalDate;

/**
 * An MCP token as the administration shows it; never the token itself.
 *
 * @param tokenPrefix first characters of the token, to recognize it
 * @param expiresOn   last day the token is valid, {@code null} if it never expires
 * @param revokedAt   time of the revocation, {@code null} if not revoked
 * @param lastUsedAt  time of the last use (accurate to the minute), {@code null} if never used
 */
public record McpTokenInfo(String id, String name, String tokenPrefix, McpScope scope, LocalDate expiresOn,
        McpTokenStatus status, String revokedAt, String createdBy, String createdAt, String lastUsedAt) {
}
