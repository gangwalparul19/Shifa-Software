package com.shifa.oms.quikshipx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link OrderShipment} rows.
 *
 * <p>Lookups by order back the create/confirm hooks and order-detail enrichment
 * (one shipment per order); by order code the allot step (the courier client has
 * only the order code); by AWB the tracking poll; and {@link #findByAwbIsNotNull}
 * feeds the scheduled tracking reconciliation.
 */
public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {

    Optional<OrderShipment> findByOrderId(Long orderId);

    Optional<OrderShipment> findByOrderCode(String orderCode);

    Optional<OrderShipment> findByAwb(String awb);

    boolean existsByOrderId(Long orderId);

    /** Shipments for a set of orders (batch enrichment of the Orders list — no N+1). */
    List<OrderShipment> findByOrderIdIn(java.util.Collection<Long> orderIds);

    /** All shipments that have an AWB (tracking-poll candidate set). */
    List<OrderShipment> findByAwbIsNotNull();
}
