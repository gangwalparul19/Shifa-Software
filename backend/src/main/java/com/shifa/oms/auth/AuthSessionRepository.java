package com.shifa.oms.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/** Persistence operations for server-authoritative authenticated sessions. */
public interface AuthSessionRepository extends JpaRepository<AuthSession, Long> {

    Optional<AuthSession> findBySessionIdHash(String sessionIdHash);

    /**
     * Atomically accepts activity only while the session is active, belongs to
     * the claimed user/version, and has not crossed the idle boundary. The
     * strict comparison makes lastActivityAt == cutoff expired.
     */
    @Modifying
    @Query("""
            UPDATE AuthSession s
               SET s.lastActivityAt = :now
             WHERE s.sessionIdHash = :hash
               AND s.userId = :userId
               AND s.sessionVersion = :sessionVersion
               AND s.revokedAt IS NULL
               AND s.lastActivityAt > :cutoff
            """)
    int touchIfActive(@Param("hash") String hash,
                      @Param("userId") Long userId,
                      @Param("sessionVersion") long sessionVersion,
                      @Param("now") LocalDateTime now,
                      @Param("cutoff") LocalDateTime cutoff);

    /** Rebinds a still-active session after a password change bumps user version. */
    @Modifying
    @Query("""
            UPDATE AuthSession s
               SET s.sessionVersion = :newVersion,
                   s.lastActivityAt = :now
             WHERE s.sessionIdHash = :hash
               AND s.userId = :userId
               AND s.revokedAt IS NULL
               AND s.lastActivityAt > :cutoff
            """)
    int rebindIfActive(@Param("hash") String hash,
                       @Param("userId") Long userId,
                       @Param("newVersion") long newVersion,
                       @Param("now") LocalDateTime now,
                       @Param("cutoff") LocalDateTime cutoff);
}
