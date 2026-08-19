package com.shifa.oms.gst;

import com.shifa.oms.gst.domain.GstEngine.HsnRow;
import com.shifa.oms.gst.domain.GstEngine.RateWiseRow;
import com.shifa.oms.gst.domain.GstEngine.StateWiseRow;
import com.shifa.oms.gst.dto.GstReportResponse;

/**
 * Renders a {@link GstReportResponse} as a multi-section CSV for the CA to use
 * while filing on the GST portal (CA GST dashboard, Req 7). Pure/no Spring: the
 * exported figures are exactly what the report/dashboard show (Property 6).
 */
public final class GstReportExporter {

    private GstReportExporter() {
    }

    public static String toCsv(GstReportResponse r) {
        StringBuilder sb = new StringBuilder();
        // Header: seller identity + period.
        line(sb, "Shifa Herbal Remedies — GST Report");
        line(sb, "Legal name", nn(r.seller().legalName()));
        line(sb, "GSTIN", nn(r.seller().gstin()));
        line(sb, "State", nn(r.seller().state()), "State code", nn(r.seller().stateCode()));
        line(sb, "Period from", String.valueOf(r.from()), "to", String.valueOf(r.to()));
        if (!r.sellerStateConfigured()) {
            line(sb, "WARNING", "Seller state not configured — supplies classified as inter-state (IGST).");
        }
        sb.append('\n');

        // GSTR-3B summary.
        line(sb, "GSTR-3B Summary");
        line(sb, "Taxable value", "CGST", "SGST", "IGST", "Total tax", "Invoice value");
        line(sb, money(r.summary().taxableOutward()), money(r.summary().outputCgst()),
                money(r.summary().outputSgst()), money(r.summary().outputIgst()),
                money(r.summary().outputTotal()), money(r.summary().invoiceValue()));
        sb.append('\n');

        // Rate-wise.
        line(sb, "Rate-wise summary");
        line(sb, "Rate %", "Taxable", "CGST", "SGST", "IGST", "Invoice value");
        for (RateWiseRow row : r.rateWise()) {
            line(sb, money(row.rate()), money(row.taxable()), money(row.cgst()),
                    money(row.sgst()), money(row.igst()), money(row.invoiceValue()));
        }
        sb.append('\n');

        // HSN-wise.
        line(sb, "HSN-wise summary");
        line(sb, "HSN", "Description", "Quantity", "Taxable", "CGST", "SGST", "IGST");
        for (HsnRow row : r.hsn()) {
            line(sb, nn(row.hsn()), nn(row.description()), plain(row.quantity()), money(row.taxable()),
                    money(row.cgst()), money(row.sgst()), money(row.igst()));
        }
        sb.append('\n');

        // State-wise.
        line(sb, "State-wise summary (place of supply)");
        line(sb, "State", "Type", "Taxable", "CGST", "SGST", "IGST");
        for (StateWiseRow row : r.stateWise()) {
            line(sb, nn(row.state()), String.valueOf(row.type()), money(row.taxable()),
                    money(row.cgst()), money(row.sgst()), money(row.igst()));
        }
        return sb.toString();
    }

    private static void line(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells[i]));
        }
        sb.append('\n');
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private static String money(java.math.BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(java.math.BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String nn(String v) {
        return v == null ? "" : v;
    }
}
