package com.shifa.oms.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * Server-side state for one authenticated browser session. The value in
 * {@code sessionIdHash} is a SHA-256 digest; the opaque JWT sid is never stored.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id_hash", nullable = false, unique = true,
            length = 64, columnDefinition = "CHAR(64)")
    private String sessionIdHash;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    protected AuthSession() {
        // Required by JPA.
    }

    public AuthSession(String sessionIdHash, Long userId, long sessionVersion,
                       LocalDateTime createdAt, LocalDateTime lastActivityAt) {
        this.sessionIdHash = sessionIdHash;
        this.userId = userId;
        this.sessionVersion = sessionVersion;
        this.createdAt = createdAt;
        this.lastActivityAt = lastActivityAt;
    }

    public Long getId() { return id; }
    public String getSessionIdHash() { return sessionIdHash; }
    public Long getUserId() { return userId; }
    public long getSessionVersion() { return sessionVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastActivityAt() { return lastActivityAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }

    public void setRevokedAt(LocalDateTime revokedAt) { this.revokedAt = revokedAt; }
}
