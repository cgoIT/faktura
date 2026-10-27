package de.cgoit.faktura.mcp.web;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import de.cgoit.faktura.mcp.CreatedMcpToken;
import de.cgoit.faktura.mcp.McpScope;
import de.cgoit.faktura.mcp.McpTokenInfo;
import de.cgoit.faktura.mcp.McpTokenService;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Administration of the MCP tokens (the filter chain of the web interface restricts the route to administrators where
 * users exist). A new token is shown once, in the answer to the creating request, and is not kept in the session.
 */
@Controller
@RequestMapping("/settings/mcp-tokens")
@ConditionalOnProperty(name = "faktura.mcp.enabled", havingValue = "true")
class McpTokenController {

    private static final DateTimeFormatter LAST_USED = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final McpTokenService tokens;
    private final Clock clock;
    private final String baseUrl;

    McpTokenController(McpTokenService tokens, Clock clock, @Value("${faktura.base-url:}") String baseUrl) {
        this.tokens = tokens;
        this.clock = clock;
        this.baseUrl = baseUrl.strip().replaceAll("/+$", "");
    }

    /**
     * A token as the list shows it.
     *
     * @param lastUsed time of the last use in local time, {@code null} if never used
     */
    record Row(McpTokenInfo token, String lastUsed) {
    }

    @GetMapping
    String list(Model model) {
        return page(model, null);
    }

    @PostMapping
    String create(@RequestParam String name, @RequestParam McpScope scope, @RequestParam int validityDays,
            Model model) {
        try {
            return page(model, tokens.create(name, scope, validityDays));
        } catch (RuleViolationException e) {
            model.addAttribute("error", e.messageKey());
            model.addAttribute("name", name);

            return page(model, null);
        }
    }

    @PostMapping("/{id}/revoke")
    String revoke(@PathVariable String id, RedirectAttributes redirect) {
        try {
            tokens.revoke(id);
            redirect.addFlashAttribute("message", "mcp.token.revoked");
        } catch (RuleViolationException e) {
            redirect.addFlashAttribute("error", e.messageKey());
        }

        return "redirect:/settings/mcp-tokens";
    }

    /**
     * The address a client connects to: the configured address of the application, else the one of this request
     * (which behind the proxy follows its forwarded headers).
     */
    private String mcpUrl() {
        return baseUrl.isEmpty()
                ? ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/mcp")
                        .build()
                        .toUriString()
                : baseUrl + "/mcp";
    }

    private String page(Model model, CreatedMcpToken created) {
        model.addAttribute("tokens", tokens.list().stream()
                .map(token -> new Row(token, token.lastUsedAt() == null ? null
                        : LAST_USED.format(Instant.parse(token.lastUsedAt()).atZone(clock.getZone()))))
                .toList());
        model.addAttribute("validityDays", tokens.validityDays());
        model.addAttribute("scopes", McpScope.values());
        model.addAttribute("created", created);
        model.addAttribute("mcpUrl", mcpUrl());
        model.addAttribute("headerName", tokens.tokenHeader());
        model.addAttribute("claudeCodeCommand", "claude mcp add --transport http faktura " + mcpUrl()
                + " --header \"" + tokens.tokenHeader() + ": <Token>\"");

        return "mcp/tokens";
    }
}
