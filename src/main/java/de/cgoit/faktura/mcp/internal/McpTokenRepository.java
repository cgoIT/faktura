package de.cgoit.faktura.mcp.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence of the MCP tokens. Rows are never deleted.
 */
@Repository
public class McpTokenRepository {

    private static final String SELECT = """
            SELECT id, name, token_prefix, scope, expires_on, revoked_at, created_by, created_at, last_used_at
            FROM mcp_token
            """;

    private final JdbcClient jdbc;

    public McpTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A stored token without its hash.
     */
    public record Row(String id, String name, String tokenPrefix, String scope, String expiresOn, String revokedAt,
            String createdBy, String createdAt, String lastUsedAt) {
    }

    public void insert(String id, String name, String tokenHash, String tokenPrefix, String scope, String expiresOn,
            String createdBy, String now) {
        jdbc.sql("""
                        INSERT INTO mcp_token (id, name, token_hash, token_prefix, scope, expires_on, created_by, created_at)
                        VALUES (:id, :name, :hash, :prefix, :scope, :expiresOn, :createdBy, :now)
                        """)
                .param("id", id)
                .param("name", name)
                .param("hash", tokenHash)
                .param("prefix", tokenPrefix)
                .param("scope", scope)
                .param("expiresOn", expiresOn)
                .param("createdBy", createdBy)
                .param("now", now)
                .update();
    }

    public List<Row> findAll() {
        return jdbc.sql(SELECT + " ORDER BY created_at DESC")
                .query(McpTokenRepository::map)
                .list();
    }

    /**
     * Finds a token that is neither revoked nor past its last day (a token without an end date never expires).
     *
     * @param today ISO date of today; the token is valid through its {@code expires_on}
     */
    public Optional<Row> findValid(String tokenHash, String today) {
        return jdbc.sql(SELECT + " WHERE token_hash = :hash AND revoked_at IS NULL AND (expires_on IS NULL OR expires_on >= :today)")
                .param("hash", tokenHash)
                .param("today", today)
                .query(McpTokenRepository::map)
                .optional();
    }

    /**
     * Revokes a token that is not revoked yet.
     *
     * @return {@code false} if there is no such token or it was revoked already
     */
    public boolean revoke(String id, String now) {
        return jdbc.sql("UPDATE mcp_token SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL")
                .param("now", now)
                .param("id", id)
                .update() == 1;
    }

    /**
     * Records a use, but only if the last one is older than {@code threshold}, so a busy client does not write on
     * every request.
     */
    public void touch(String id, String now, String threshold) {
        jdbc.sql("""
                        UPDATE mcp_token SET last_used_at = :now
                        WHERE id = :id AND (last_used_at IS NULL OR last_used_at < :threshold)
                        """)
                .param("now", now)
                .param("id", id)
                .param("threshold", threshold)
                .update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("name"), rs.getString("token_prefix"),
                rs.getString("scope"), rs.getString("expires_on"), rs.getString("revoked_at"),
                rs.getString("created_by"), rs.getString("created_at"), rs.getString("last_used_at"));
    }
}
