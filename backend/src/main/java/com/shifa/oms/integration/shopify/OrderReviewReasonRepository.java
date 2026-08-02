package com.shifa.oms.integration.shopify;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Data access for {@link OrderReviewReason} ({@code order_review_reasons}, V49). */
public interface OrderReviewReasonRepository extends JpaRepository<OrderReviewReason, Long> {

    boolean existsByOrderIdAndReason(Long orderId, ReviewReason reason);

    List<OrderReviewReason> findByOrderIdOrderByIdAsc(Long orderId);

    /** Batch-loads reasons for a page of orders, so the review queue avoids an N+1. */
    List<OrderReviewReason> findByOrderIdInOrderByOrderIdAscIdAsc(Collection<Long> orderIds);

    /** The distinct orders currently needing review, newest first. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT DISTINCT r.orderId FROM OrderReviewReason r ORDER BY r.orderId DESC
            """)
    List<Long> findDistinctOrderIds();
}
