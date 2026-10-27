package de.cgoit.faktura.mcp.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.cgoit.faktura.mcp.McpScope;
import de.cgoit.faktura.mcp.internal.McpTools;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Speaks the Model Context Protocol (JSON-RPC over HTTP, stateless) for the tools of {@link McpTools}. Only the parts
 * a tool server needs are implemented: {@code initialize}, {@code ping}, {@code tools/list}, {@code tools/call}.
 * Requests without an id are notifications and get an empty answer. The route is protected by its own filter chain
 * (bearer token), see {@code McpSecurityConfiguration}.
 */
@RestController
@ConditionalOnProperty(name = "faktura.mcp.enabled", havingValue = "true")
class McpController {

    private static final List<String> VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");

    // shown to the assistant at the start of a session; keeps it from silently dropping what the user asked for
    private static final String INSTRUCTIONS = """
            faktura creates invoice and offer drafts and reads open items. A person finalizes, sends and books in the \
            web interface. Before creating a draft, check that every detail the user named has a matching parameter \
            of the tool ('subject' is the internal designation or note). If something has no parameter, or you are \
            unsure which parameter is meant, ask the user first and create the draft only after the answer; never \
            drop a detail silently. Search the customer and the catalog entries first and ask when several match.""";

    private final McpTools tools;

    McpController(McpTools tools) {
        this.tools = tools;
    }

    /**
     * One JSON-RPC message; {@code id} is a number or a string, absent for a notification.
     */
    record Request(Object id, String method, Map<String, Object> params) {

        /**
         * Hides the parameters, which can contain customer data, from logs.
         */
        @Override
        public String toString() {
            return "Request[method=" + method + "]";
        }
    }

    @PostMapping(value = "/mcp", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> handle(@RequestBody Request request, Authentication authentication) {
        if (request.id() != null && !(request.id() instanceof String || request.id() instanceof Number)) {
            return ResponseEntity.badRequest()
                    .body(error(null, -32600, "Invalid request"));
        }

        if (request.id() == null || request.method() == null) {
            return ResponseEntity.accepted()
                    .build();
        }

        var params = request.params() == null ? Map.<String, Object>of() : request.params();
        var id = request.id();

        return ResponseEntity.ok(switch (request.method()) {
            case "initialize" -> result(id, initialize(params));
            case "ping" -> result(id, Map.of());
            case "tools/list" -> result(id, Map.of("tools", tools.list(scopeOf(authentication))));
            case "tools/call" -> call(id, params, scopeOf(authentication));
            default -> error(id, -32601, "Method not found");
        });
    }

    /**
     * A body that is not a single JSON-RPC message (broken JSON, a batch array) gets a protocol error, not a page.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable() {
        return ResponseEntity.badRequest()
                .body(error(null, -32700, "Parse error"));
    }

    private Map<String, Object> call(Object id, Map<String, Object> params, McpScope scope) {
        if (!(params.get("name") instanceof String name)) {
            return error(id, -32602, "Tool name missing");
        }

        @SuppressWarnings("unchecked")
        var arguments = params.get("arguments") instanceof Map<?, ?> map ? (Map<String, Object>) map : null;

        try {
            return result(id, tools.call(name, arguments, scope));
        } catch (IllegalArgumentException e) {
            return error(id, -32602, "Unknown tool");
        } catch (RuntimeException e) {
            // fixed text: the message of a service exception may carry customer data
            return error(id, -32603, "Internal error");
        }
    }

    /**
     * The scope the filter granted; without a recognizable authority a request may only read.
     */
    private static McpScope scopeOf(Authentication authentication) {
        return authentication.getAuthorities()
                .stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_" + McpScope.DRAFT.name()))
                ? McpScope.DRAFT
                : McpScope.READ;
    }

    private static Map<String, Object> initialize(Map<String, Object> params) {
        var wanted = params.get("protocolVersion");
        var version = VERSIONS.contains(wanted) ? wanted : VERSIONS.getFirst();

        return Map.of("protocolVersion", version,
                "capabilities", Map.of("tools", Map.of("listChanged", false)),
                "serverInfo", Map.of("name", "faktura", "version", "1"),
                "instructions", INSTRUCTIONS);
    }

    private static Map<String, Object> result(Object id, Object result) {
        var response = new LinkedHashMap<String, Object>();

        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);

        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        var response = new LinkedHashMap<String, Object>();

        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));

        return response;
    }
}
