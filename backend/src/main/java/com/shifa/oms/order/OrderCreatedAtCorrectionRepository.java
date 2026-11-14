package com.shifa.oms.order;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence boundary for immutable order punched-date corrections. */
public interface OrderCreatedAtCorrectionRepository extends JpaRepository<OrderCreatedAtCorrection, Long> {

    /** Batch lookup used to mark corrected dates on the Orders list without an N+1 query. */
    java.util.List<OrderCreatedAtCorrection> findByOrderIdIn(java.util.Collection<Long> orderIds);

    /** Whether an order has at least one immutable punched-date correction. */
    boolean existsByOrderId(Long orderId);
}
