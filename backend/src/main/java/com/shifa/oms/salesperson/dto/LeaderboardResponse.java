package com.shifa.oms.salesperson.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Sales leaderboard for a chosen period (default: this month) plus the caller's own standing
 * ({@code GET /api/my-day/leaderboard}) — light gamification to motivate the team. {@code rows}
 * are the top salespeople by revenue in the period (excluding rejected/cancelled).
 * {@code myRank} is the caller's 1-based rank among all salespeople (null for an ADMIN or a
 * salesperson with no qualifying orders in the period), and {@code myStreakDays} is their run of
 * consecutive days with at least one order (ending today or yesterday). The streak is not tied to
 * the period: it always looks at the caller's whole history.
 *
 * @param period      the period applied ({@code PerformancePeriod} name)
 * @param periodLabel human label, e.g. "Last 7 Days" or "2026-10-01 to 2026-10-10"
 * @param from        first day covered, or {@code null} for "Overall"
 * @param to          last day covered (inclusive), or {@code null} for "Overall"
 */
public record LeaderboardResponse(
        List<LeaderboardRow> rows,
        Integer myRank,
        BigDecimal myRevenue,
        int myStreakDays,
        String period,
        String periodLabel,
        LocalDate from,
        LocalDate to) {

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
