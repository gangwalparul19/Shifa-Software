package com.shifa.oms.dashboard.dto;

import java.math.BigDecimal;

/**
 * Real-time headline statistics pushed over SSE and available on demand
 * (Req 19.5): today's order count, today's collection (amount received on
 * orders created today), the total COD still to collect from couriers, and the
 * total loss amount still to claim from couriers.
 */
public record LiveStats(
        long realtimeOrderCount,
        BigDecimal todaysCollection,
        BigDecimal totalCodToCollect,
        BigDecimal totalLossToClaim) {
}
