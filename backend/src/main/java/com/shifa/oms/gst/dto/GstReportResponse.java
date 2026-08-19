package com.shifa.oms.gst.dto;

import com.shifa.oms.gst.domain.GstEngine.Gstr3bSummary;
import com.shifa.oms.gst.domain.GstEngine.HsnRow;
import com.shifa.oms.gst.domain.GstEngine.RateWiseRow;
import com.shifa.oms.gst.domain.GstEngine.StateWiseRow;

import java.time.LocalDate;
import java.util.List;

/**
 * The filing-ready outward GST report for a period (CA GST dashboard, Reqs 4, 5,
 * 7): seller identity, the period, and the rate-wise / HSN-wise / state-wise
 * summaries plus the GSTR-3B-style total.
 */
public record GstReportResponse(
        Seller seller,
        LocalDate from,
        LocalDate to,
        boolean sellerStateConfigured,
        List<RateWiseRow> rateWise,
        List<HsnRow> hsn,
        List<StateWiseRow> stateWise,
        Gstr3bSummary summary
) {

    /** The seller's statutory identity used on the report header. */
    public record Seller(String legalName, String gstin, String state, String stateCode) {
    }
}
