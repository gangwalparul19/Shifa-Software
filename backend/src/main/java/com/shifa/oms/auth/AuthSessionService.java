package com.shifa.oms.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns server-authoritative sliding idle sessions. Browser clocks and JWT hints
 * are never used to accept activity; only this service's conditional database
 * update decides whether a session remains alive.
 */
@Service
public class AuthSessionService {

    private final AuthSessionRepository repository;
    private final Duration idleTtl;
    private final Clock clock;

    @Autowired
    public AuthSessionService(AuthSessionRepository repository, JwtProperties properties) {
        this(repository, properties, Clock.systemUTC());
    }

    /** Fixed-clock constructor retained for focused unit tests. */
    AuthSessionService(AuthSessionRepository repository, JwtProperties properties, Clock clock) {
        this.repository = repository;
        this.idleTtl = properties.getSessionTtl();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** Creates a session and returns the raw sid used only in signed JWTs. */
    @Transactional
    public SessionHandle create(User user) {
        String sid = UUID.randomUUID().toString();
        LocalDateTime now = now();
        repository.save(new AuthSession(hash(sid), user.getId(), user.getSessionVersion(), now, now));
        return new SessionHandle(sid, idleExpiry(now));
    }

    /**
     * Atomically records authenticated activity. Empty means revoked, stale,
     * mismatched, or exactly at the idle boundary.
     */
    @Transactional
    public Optional<SessionHandle> touch(String sid, Long userId, long sessionVersion) {
        if (sid == null || sid.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = now();
        LocalDateTime cutoff = now.minus(idleTtl);
        int changed = repository.touchIfActive(hash(sid), userId, sessionVersion, now, cutoff);
        return changed == 1 ? Optional.of(new SessionHandle(sid, idleExpiry(now))) : Optional.empty();
    }

    /** Rebinds a current session after password change increments user version. */
    @Transactional
    public Optional<SessionHandle> rebind(String sid, Long userId, long newSessionVersion) {
        if (sid == null || sid.isBlank()) {
            return Optional.empty();
        }
        LocalDateTime now = now();
        int changed = repository.rebindIfActive(hash(sid), userId, newSessionVersion, now,
                now.minus(idleTtl));
        return changed == 1 ? Optional.of(new SessionHandle(sid, idleExpiry(now))) : Optional.empty();
    }

    public long idleTtlSeconds() {
        return idleTtl.toSeconds();
    }

    static String hash(String sid) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sid.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
    }

    private Instant idleExpiry(LocalDateTime now) {
        return now.toInstant(java.time.ZoneOffset.UTC).plus(idleTtl);
    }

    public record SessionHandle(String sessionId, Instant idleExpiresAt) {
        public long idleExpiresAtEpochSeconds() {
            return idleExpiresAt.getEpochSecond();
        }

        public long idleExpiresAtEpochMillis() {
            return idleExpiresAt.toEpochMilli();
        }
    }
}
