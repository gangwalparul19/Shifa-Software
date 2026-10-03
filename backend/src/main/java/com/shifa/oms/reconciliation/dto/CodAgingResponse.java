package com.shifa.oms.reconciliation.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * COD aging summary for the accountant/CA (cod-aging enhancement): unsettled COD
 * receivables grouped into aging buckets by how long they have been outstanding,
 * plus a courier-SLA flag when money has been owed beyond the expected payout
 * window. Read-only; derived from the {@code receivables} ledger.
 *
 * @param buckets      aging buckets, oldest-first in display order
 * @param totalOutstanding total unsettled COD across all buckets
 * @param slaDays       the expected courier payout window (days) used for the SLA flag
 * @param overSlaCount  how many receivables are older than {@code slaDays}
 * @param overSlaAmount total amount owed beyond the SLA window (the chase figure)
 */
public record CodAgingResponse(
        List<Bucket> buckets,
        BigDecimal totalOutstanding,
        int slaDays,
        int overSlaCount,
        BigDecimal overSlaAmount
) {

    /**
     * One aging bucket.
     *
     * @param label    human label, e.g. "0–7 days"
     * @param minDays  inclusive lower bound of the age range (days)
     * @param maxDays  inclusive upper bound, or {@code null} for the open-ended oldest bucket
     * @param count    number of unsettled COD receivables in the bucket
     * @param amount   total unsettled COD amount in the bucket
     */
    public record Bucket(String label, int minDays, Integer maxDays, int count, BigDecimal amount) {
    }
}
