package com.shifa.oms.order.dto;

/**
 * Result of {@code GET /api/orders/duplicate-check?mobile=} (Req 22.2):
 * whether one or more prior orders exist for the mobile number, and how many.
 *
 * <p>Also carries a SAME-DAY duplicate signal: a customer can reach two
 * salespeople the same day and get the same order punched twice. When an active
 * (not rejected/cancelled) order already exists for this mobile TODAY, the
 * order-entry form warns the salesperson before they submit — including the
 * existing order's code, who created it, and whether that was the current user
 * (so the message reads "you already placed…" vs "salesperson X already
 * placed…"). The server still hard-blocks a same-day duplicate on submit; these
 * fields are only for the pre-submit warning.
 *
 * @param mobile                 the queried mobile (trimmed)
 * @param hasPriorOrders         whether ANY prior order exists (all-time)
 * @param priorOrderCount        the all-time count of orders for this mobile
 * @param hasTodayOrder          whether an ACTIVE order already exists today
 * @param todayOrderCode         the existing today order's code (null when none)
 * @param todaySalespersonName   display name of who placed today's order (null when none)
 * @param todayCreatedByMe       whether today's order was placed by the current user
 */
public record DuplicateCheckResponse(
        String mobile,
        boolean hasPriorOrders,
        long priorOrderCount,
        boolean hasTodayOrder,
        String todayOrderCode,
        String todaySalespersonName,
        boolean todayCreatedByMe) {

    /** Convenience for the common "no same-day duplicate" case. */
    public static DuplicateCheckResponse of(String mobile, long priorOrderCount) {
        return new DuplicateCheckResponse(
                mobile, priorOrderCount > 0, priorOrderCount, false, null, null, false);
    }
}
