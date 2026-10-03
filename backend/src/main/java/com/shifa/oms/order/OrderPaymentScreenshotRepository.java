package com.shifa.oms.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Read access to {@code order_payment_screenshots} for duplicate-proof detection
 * (payment-verifier enhancement, V72).
 *
 * <p>The entity is owned by {@link OrderEntity} through a unidirectional
 * {@code @OneToMany} join column, so it has no mapped {@code orderId} field; the
 * cross-order lookups below use native queries against the table's
 * {@code order_id} + {@code content_hash} columns.
 */
public interface OrderPaymentScreenshotRepository extends JpaRepository<OrderPaymentScreenshot, Long> {

    /**
     * The DISTINCT order ids (other than {@code excludeOrderId}) that have a
     * payment proof with the same {@code content_hash} — i.e. the same image
     * reused on another order. Empty when the hash is unique.
     */
    @Query(value = "SELECT DISTINCT ps.order_id FROM order_payment_screenshots ps "
            + "WHERE ps.content_hash = :hash AND ps.order_id <> :excludeOrderId",
            nativeQuery = true)
    List<Long> findOtherOrderIdsWithHash(@Param("hash") String hash,
                                         @Param("excludeOrderId") Long excludeOrderId);

    /** The content hashes attached to one order (its proofs), ignoring nulls. */
    @Query(value = "SELECT ps.content_hash FROM order_payment_screenshots ps "
            + "WHERE ps.order_id = :orderId AND ps.content_hash IS NOT NULL",
            nativeQuery = true)
    List<String> findHashesForOrder(@Param("orderId") Long orderId);
}
