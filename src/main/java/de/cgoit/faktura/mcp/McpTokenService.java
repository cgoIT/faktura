package de.cgoit.faktura.mcp;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.cgoit.faktura.audit.AuditAction;
import de.cgoit.faktura.audit.AuditObjectType;
import de.cgoit.faktura.audit.AuditService;
import de.cgoit.faktura.mcp.internal.McpProperties;
import de.cgoit.faktura.mcp.internal.McpTokenRepository;
import de.cgoit.faktura.mcp.internal.TokenHashes;
import de.cgoit.faktura.shared.CurrentUser;
import de.cgoit.faktura.shared.RuleViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Management of the access tokens of the MCP server. A token is random, expires on a day chosen when it is created
 * and can be revoked at once; only its hash is stored. Creating and revoking are written to the audit log (without the
 * token). Tokens are never deleted.
 */
@Service
public class McpTokenService {

    static final int MAX_NAME_LENGTH = 90;

    private static final String PREFIX = "fkt_";
    private static final int PREFIX_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpTokenRepository tokens;
    private final McpProperties properties;
    private final AuditService audit;
    private final CurrentUser currentUser;
    private final Clock clock;

    public McpTokenService(McpTokenRepository tokens, McpProperties properties, AuditService audit,
            CurrentUser currentUser, Clock clock) {
        this.tokens = tokens;
        this.properties = properties;
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Lists all tokens, newest first.
     */
    @Transactional(readOnly = true)
    public List<McpTokenInfo> list() {
        return tokens.findAll().stream()
                .map(this::toInfo)
                .toList();
    }

    /**
     * Validity periods in days an administrator can choose from.
     */
    public List<Integer> validityDays() {
        return properties.validityDays();
    }

    /**
     * Creates a token valid for the given number of days (today included in the count: 30 days means through
     * today + 30). The result carries the token in plain text; it cannot be shown again.
     *
     * @throws RuleViolationException if the name is empty or too long, or the period is not one of the offered ones
     */
    @Transactional
    public CreatedMcpToken create(String name, McpScope scope, int validityDays) {
        var cleanName = name == null ? "" : name.strip();

        if (cleanName.isEmpty() || cleanName.length() > MAX_NAME_LENGTH) {
            throw new RuleViolationException("mcp.token.error.name");
        }

        if (scope == null) {
            throw new RuleViolationException("mcp.token.error.scope");
        }

        if (!properties.validityDays().contains(validityDays)) {
            throw new RuleViolationException("mcp.token.error.validity");
        }

        var bytes = new byte[32];

        RANDOM.nextBytes(bytes);

        var token = PREFIX + HexFormat.of().formatHex(bytes);
        var id = UUID.randomUUID().toString();
        var expiresOn = LocalDate.now(clock).plusDays(validityDays);

        tokens.insert(id, cleanName, TokenHashes.sha256Hex(token), token.substring(0, PREFIX_LENGTH), scope.name(),
                expiresOn.toString(), currentUser.username(), Instant.now(clock).toString());
        audit.record(AuditObjectType.MCP_TOKEN, id, AuditAction.MCP_TOKEN_CREATE,
                Map.of("scope", scope.name(), "expiresOn", expiresOn.toString()));

        var created = tokens.findAll().stream()
                .filter(row -> row.id().equals(id))
                .findFirst()
                .orElseThrow();

        return new CreatedMcpToken(toInfo(created), token);
    }

    /**
     * Revokes a token; it stops working at once.
     *
     * @throws RuleViolationException if there is no such token or it is revoked already
     */
    @Transactional
    public void revoke(String id) {
        if (!tokens.revoke(id, Instant.now(clock).toString())) {
            throw new RuleViolationException("mcp.token.error.unknown");
        }

        audit.record(AuditObjectType.MCP_TOKEN, id, AuditAction.MCP_TOKEN_REVOKE, Map.of());
    }

    private McpTokenInfo toInfo(McpTokenRepository.Row row) {
        var expiresOn = LocalDate.parse(row.expiresOn());
        var status = row.revokedAt() != null
                ? McpTokenStatus.REVOKED
                : expiresOn.isBefore(LocalDate.now(clock)) ? McpTokenStatus.EXPIRED : McpTokenStatus.ACTIVE;

        return new McpTokenInfo(row.id(), row.name(), row.tokenPrefix(), McpScope.valueOf(row.scope()), expiresOn,
                status, row.revokedAt(), row.createdBy(), row.createdAt(), row.lastUsedAt());
    }
}
