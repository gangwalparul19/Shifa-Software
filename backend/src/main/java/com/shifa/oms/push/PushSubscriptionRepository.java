package com.shifa.oms.push;

import com.shifa.oms.auth.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistence for staff Web Push subscriptions (FEATURE-ROADMAP §8.3).
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscriptionEntity, Long> {

    List<PushSubscriptionEntity> findByUserId(Long userId);

    boolean existsByEndpoint(String endpoint);

    long deleteByEndpoint(String endpoint);

    long deleteByUserIdAndEndpoint(Long userId, String endpoint);

    /**
     * All subscriptions belonging to active users holding the given role — used to
     * fan a role-addressed push out to every device of every user in that role.
     */
    @Query("""
            SELECT ps FROM PushSubscriptionEntity ps
            WHERE ps.userId IN (
                SELECT u.id FROM User u WHERE u.role = :role AND u.active = true
            )
            """)
    List<PushSubscriptionEntity> findByUserRole(@Param("role") Role role);
}
