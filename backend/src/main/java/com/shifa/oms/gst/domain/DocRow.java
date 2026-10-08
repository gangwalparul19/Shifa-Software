package com.shifa.oms.gst.domain;

/**
 * One row of the GSTR-1 <strong>Table 13</strong> Documents-Issued summary (GST filing compliance,
 * Req 4).
 *
 * <p>Per invoice-number series, this records the from/to number range and the counts of documents
 * issued and cancelled in the reporting period. An order's invoice counts as cancelled when the
 * order is in a cancelled/rejected state, and as issued otherwise (Req 4.2).
 *
 * @param natureOfDocument the document nature, e.g. {@code "Invoices for outward supply"}
 * @param fromNumber       the lowest invoice number in the series for the period
 * @param toNumber         the highest invoice number in the series for the period
 * @param totalCount       the total number of documents issued in the series for the period
 * @param cancelledCount   the number of those documents that were cancelled/rejected
 */
public record DocRow(
        String natureOfDocument,
        String fromNumber,
        String toNumber,
        int totalCount,
        int cancelledCount) {
}
