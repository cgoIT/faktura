-- Access tokens of the MCP server (managed by an administrator). Only the SHA-256 hash of a token is stored, the token
-- itself is shown once when it is created. Rows are never deleted: a token is revoked or expires.
CREATE TABLE mcp_token (
    id           VARCHAR(36)  PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 of the token as lower-case hex
    token_prefix VARCHAR(12)  NOT NULL,          -- first characters of the token, to recognize it in the list
    scope        VARCHAR(10)  NOT NULL,          -- READ | DRAFT (DRAFT includes READ)
    expires_on   VARCHAR(10)  NOT NULL,          -- last day the token is valid (ISO date)
    revoked_at   VARCHAR(30),
    created_by   VARCHAR(100),
    created_at   VARCHAR(30)  NOT NULL,
    last_used_at VARCHAR(30),                    -- written at most once a minute
    CONSTRAINT ck_mcp_token_scope CHECK (scope IN ('READ', 'DRAFT'))
);
