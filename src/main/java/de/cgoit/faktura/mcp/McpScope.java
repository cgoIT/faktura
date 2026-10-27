package de.cgoit.faktura.mcp;

/**
 * What an MCP token may do: {@link #READ} only reads, {@link #DRAFT} also creates drafts.
 */
public enum McpScope {
    READ, DRAFT;

    /**
     * Tells whether a tool that writes (creates a draft) is allowed.
     */
    public boolean allowsWriting() {
        return this == DRAFT;
    }
}
