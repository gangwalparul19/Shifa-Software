package com.shifa.oms.quikshipx;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Persistence for the per-order QuikShipX scan timeline
 * ({@link ShipmentTrackingEvent}, V95). The stored set for an order is replaced
 * wholesale on each successful track (delete-then-insert), so reads always get
 * the latest timeline QuikShipX returned, ordered newest first.
 */
public interface ShipmentTrackingEventRepository extends JpaRepository<ShipmentTrackingEvent, Long> {

    /** The order's scan events, newest first (by {@code sort_order}). */
    List<ShipmentTrackingEvent> findByOrderIdOrderBySortOrderAsc(Long orderId);

    /** Removes every stored scan event for an order (before re-inserting the fresh set). */
    @Modifying
    @Query("delete from ShipmentTrackingEvent e where e.orderId = :orderId")
    void deleteByOrderId(@Param("orderId") Long orderId);
}
