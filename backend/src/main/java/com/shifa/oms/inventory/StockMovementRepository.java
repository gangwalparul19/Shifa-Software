package com.shifa.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for the {@link StockMovement} ledger.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

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
