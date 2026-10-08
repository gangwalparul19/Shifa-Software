package com.shifa.oms.insights.domain;

import com.shifa.oms.order.LeadSource;

/**
 * Per-channel lead volume and won counts (design &sect;Pure domain; Req 8.1, 8.2),
 * the input to the lead-source conversion insight. The conversion rate is
 * {@code won / leads}, computed via
 * {@link com.shifa.oms.lead.LeadReportAggregator#conversionRate(long, long)} so
 * the engine reuses the exact same formula the lead reports use.
 *
 * @param source the lead channel
 * @param leads  leads captured on that channel
 * @param won    leads on that channel that converted (WON)
 */
public record LeadSourceConversion(LeadSource source, long leads, long won) {
}
