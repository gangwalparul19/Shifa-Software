package com.shifa.oms.inventory.dto;

import com.shifa.oms.inventory.StockMovement;
import com.shifa.oms.inventory.StockMovementType;

import java.time.LocalDateTime;

/**
 * Read projection of a {@link StockMovement} ledger row.
 *
 * @param id           the movement id
 * @param productId    the product the movement concerns
 * @param delta        the signed quantity change
 * @param type         the movement type
 * @param reason       the recorded reason (may be {@code null})
 * @param balanceAfter the on-hand quantity immediately after the movement
 * @param createdBy    the acting user id, or {@code null} for system movements
 * @param createdAt    when the movement was recorded
 */
public record StockMovementResponse(
        Long id,
        Long productId,
        int delta,
        StockMovementType type,
        String reason,
        int balanceAfter,
        Long createdBy,
        LocalDateTime createdAt) {

    public static StockMovementResponse from(StockMovement m) {
        return new StockMovementResponse(
                m.getId(),
                m.getProductId(),
                m.getDelta(),
                m.getMovementType(),
                m.getReason(),
                m.getBalanceAfter(),
                m.getCreatedBy(),
                m.getCreatedAt());
    }
}
