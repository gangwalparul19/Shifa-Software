package com.shifa.oms.notification;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link OrderMilestoneNotification} de-duplication markers
 * (V96). The {@code UNIQUE(order_id, milestone)} constraint is the real guard:
 * the milestone notifier inserts a marker and treats a constraint violation as
 * "already notified", so each milestone fires exactly once per order.
 */
public interface OrderMilestoneNotificationRepository
        extends JpaRepository<OrderMilestoneNotification, Long> {

    /** Whether the given milestone has already been notified for the order. */
    boolean existsByOrderIdAndMilestone(Long orderId, String milestone);
}
