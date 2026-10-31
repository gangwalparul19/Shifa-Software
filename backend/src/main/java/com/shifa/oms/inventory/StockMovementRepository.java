package com.shifa.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the {@link StockMovement} ledger.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Report rows filtered in SQL; null bounds intentionally mean all time. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT m FROM StockMovement m
            WHERE (:from IS NULL OR m.createdAt >= :from)
              AND (:to IS NULL OR m.createdAt < :to)
            ORDER BY m.createdAt ASC
            """)
    List<StockMovement> findForReport(
            @org.springframework.data.repository.query.Param("from") LocalDateTime from,
            @org.springframework.data.repository.query.Param("to") LocalDateTime to);

    /** Recent movements for a product, newest first (admin drill-down). */
    List<StockMovement> findByProductIdOrderByCreatedAtDescIdDesc(Long productId);

    /**
     * All movements of a given type within an inclusive timestamp window
     * (statistical-insights-engine, design §Services): the insights computation
     * sums {@code SALE} deltas per product over the reorder lookback window.
     */
    List<StockMovement> findByMovementTypeAndCreatedAtBetween(
            StockMovementType movementType, LocalDateTime from, LocalDateTime to);

    /**
     * The latest ledger row for a product (newest by {@code created_at} then id),
     * whose {@link StockMovement#getBalanceAfter()} is the current on-hand
     * quantity used by the reorder insight (design §Services).
     */
    Optional<StockMovement> findTopByProductIdOrderByCreatedAtDescIdDesc(Long productId);
}
