package com.shifa.oms.inventory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for the {@link StockMovement} ledger.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** Recent movements for a product, newest first (admin drill-down). */
    List<StockMovement> findByProductIdOrderByCreatedAtDescIdDesc(Long productId);
}
