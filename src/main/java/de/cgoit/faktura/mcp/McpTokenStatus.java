package de.cgoit.faktura.mcp;

/**
 * State of an MCP token, derived from revocation and expiry.
 */
public enum McpTokenStatus {
    ACTIVE, EXPIRED, REVOKED
}
