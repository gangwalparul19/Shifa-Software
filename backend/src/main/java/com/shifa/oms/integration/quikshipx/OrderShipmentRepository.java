package com.shifa.oms.integration.quikshipx;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Data access for {@link OrderShipment} ({@code order_shipments}, V49). */
public interface OrderShipmentRepository extends JpaRepository<OrderShipment, Long> {

    Optional<OrderShipment> findByOrderId(Long orderId);

    /**
     * The managed-order test. Cheaper than loading the row, and it is called on every
     * transition-authority decision.
     */
    boolean existsByOrderId(Long orderId);

    /** Resolves a status event that carries QuikShipX's own shipment identifier. */
    Optional<OrderShipment> findByQuikshipxShipmentId(String quikshipxShipmentId);

    /** Resolves a status event that carries QuikShipX's own order id ({@code order_id}). */
    Optional<OrderShipment> findByQuikshipxOrderId(String quikshipxOrderId);

    /**
     * Resolves a status event that carries only the order reference we sent — the
     * fallback that matters, because the create response may never have given us a
     * shipment id (Req 6.15).
     */
    Optional<OrderShipment> findByOrderReference(String orderReference);
}
