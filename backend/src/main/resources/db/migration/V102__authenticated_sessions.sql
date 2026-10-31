-- Server-authoritative sliding idle sessions. Only a SHA-256 digest of the
-- opaque JWT sid is persisted; the raw sid is never stored in the database.
CREATE TABLE auth_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    session_id_hash CHAR(64) NOT NULL,
    user_id BIGINT NOT NULL,
    session_version BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    last_activity_at DATETIME NOT NULL,
    revoked_at DATETIME NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_auth_sessions_sid_hash UNIQUE (session_id_hash),
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX ix_auth_sessions_user_activity (user_id, last_activity_at),
    INDEX ix_auth_sessions_active (revoked_at, last_activity_at)
);
