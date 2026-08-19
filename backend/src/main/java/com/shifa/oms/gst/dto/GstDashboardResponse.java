package com.shifa.oms.gst.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The CA dashboard payload (CA GST dashboard, Req 6): the outward GST report for
 * the period plus every recorded money in-flow and out-flow, and a net cash
 * position (operational view).
 */
public record GstDashboardResponse(
        GstReportResponse report,
        MoneyFlows money
) {

    /** Period money in/out (Req 6). */
    public record MoneyFlows(
            // In-flows
            BigDecimal grossSales,       // GST-inclusive invoice value of outward supplies
            BigDecimal taxableSales,     // pre-tax taxable value
            BigDecimal outputGst,        // total output tax (CGST+SGST+IGST)
            BigDecimal amountReceived,   // money actually received in the window
            BigDecimal codCollected,     // COD collected in the window
            // Out-flows
            BigDecimal purchases,        // purchase-order totals in the window
            BigDecimal expenses,         // expenses incurred in the window
            List<CategoryAmount> expensesByCategory,
            BigDecimal refunds,          // refunds from returns in the window
            // Receivables (as of now)
            BigDecimal outstandingCod,   // COD still to be collected
            // Net
            BigDecimal netCash           // received + COD collected − purchases − expenses − refunds
    ) {
    }

    /** One expense category total. */
    public record CategoryAmount(String category, BigDecimal amount) {
    }
}
