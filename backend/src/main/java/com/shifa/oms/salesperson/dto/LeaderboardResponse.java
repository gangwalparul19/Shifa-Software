package com.shifa.oms.salesperson.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Sales leaderboard for the current month plus the caller's own standing
 * ({@code GET /api/my-day/leaderboard}) — light gamification to motivate the
 * team. {@code rows} are the top salespeople by revenue (this month, excluding
 * rejected/cancelled). {@code myRank} is the caller's 1-based rank among all
 * salespeople (null for an ADMIN or a salesperson with no qualifying orders),
 * and {@code myStreakDays} is their run of consecutive days with at least one
 * order (ending today or yesterday).
 */
public record LeaderboardResponse(
        List<LeaderboardRow> rows,
        Integer myRank,
        BigDecimal myRevenue,
        int myStreakDays) {

    /** One ranked salesperson row. {@code isMe} flags the caller's own row. */
    public record LeaderboardRow(
            int rank,
            long salespersonId,
            String name,
            BigDecimal revenue,
            long orders,
            boolean isMe) {
    }
}
