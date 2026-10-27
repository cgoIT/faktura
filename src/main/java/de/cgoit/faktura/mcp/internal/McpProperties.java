package de.cgoit.faktura.mcp.internal;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the MCP server ({@code faktura.mcp.*}).
 *
 * @param enabled       whether {@code /mcp} exists at all
 * @param token         optional emergency token from the environment with read access only; empty = none. Normal
 *                      access uses the tokens an administrator manages in the application
 * @param tokenHeader   name of a header that may carry the bare token instead of {@code Authorization: Bearer}
 * @param validityDays  validity periods offered when an administrator creates a token
 */
@ConfigurationProperties("faktura.mcp")
public record McpProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String token,
        @DefaultValue("X-API-Key") String tokenHeader, @DefaultValue({ "30", "90", "365" }) List<Integer> validityDays) {

    static final int MIN_TOKEN_LENGTH = 32;

    /**
     * Hides the token from logs.
     */
    @Override
    public String toString() {
        return "McpProperties[enabled=" + enabled + "]";
    }
}
