package de.cgoit.faktura.mcp.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the MCP server ({@code faktura.mcp.*}).
 *
 * @param enabled whether {@code /mcp} exists at all
 * @param token   bearer token every request must carry
 */
@ConfigurationProperties("faktura.mcp")
public record McpProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String token) {

    static final int MIN_TOKEN_LENGTH = 32;

    /**
     * Hides the token from logs.
     */
    @Override
    public String toString() {
        return "McpProperties[enabled=" + enabled + "]";
    }
}
