package de.cgoit.faktura.mcp;

/**
 * A newly created token with its plain text, which exists only in this result.
 */
public record CreatedMcpToken(McpTokenInfo info, String token) {

    /**
     * Hides the token from logs.
     */
    @Override
    public String toString() {
        return "CreatedMcpToken[id=" + info.id() + "]";
    }
}
