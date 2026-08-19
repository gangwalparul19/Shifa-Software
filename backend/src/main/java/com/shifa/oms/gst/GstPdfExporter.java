package com.shifa.oms.gst;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.gst.domain.GstEngine.HsnRow;
import com.shifa.oms.gst.domain.GstEngine.RateWiseRow;
import com.shifa.oms.gst.domain.GstEngine.StateWiseRow;
import com.shifa.oms.gst.dto.GstReportResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Renders the outward GST report to a branded, filing-friendly PDF using OpenPDF
 * (CA GST dashboard, Req 7): seller header, period, the GSTR-3B summary, and the
 * rate-wise / HSN-wise / state-wise tables. Figures match the dashboard exactly
 * (Property 6).
 */
@Component
public class GstPdfExporter {

    private static final Color BRAND_GREEN = new Color(0x1F, 0x7A, 0x4D);
    private static final Color DARK_GREEN = new Color(0x14, 0x53, 0x2D);
    private static final Color ROW_TINT = new Color(0xEA, 0xF3, 0xEC);
    private static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    private static final Color BODY = new Color(0x24, 0x3B, 0x30);
    private static final Color MUTED = new Color(0x6B, 0x7B, 0x72);

    private static final Font COMPANY = new Font(Font.HELVETICA, 16, Font.BOLD, DARK_GREEN);
    private static final Font META = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private static final Font SECTION = new Font(Font.HELVETICA, 11, Font.BOLD, BRAND_GREEN);
    private static final Font TH = new Font(Font.HELVETICA, 8, Font.BOLD, WHITE);
    private static final Font TD = new Font(Font.HELVETICA, 8, Font.NORMAL, BODY);
    private static final Font WARN = new Font(Font.HELVETICA, 8, Font.ITALIC, new Color(0xB0, 0x6A, 0x00));

    /** Renders the report to PDF bytes. */
    public byte[] toPdf(GstReportResponse r) {
        Document doc = new Document(PageSize.A4, 32, 32, 32, 32);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(new Paragraph(nn(r.seller().legalName(), "Shifa Herbal Remedies"), COMPANY));
            doc.add(meta("GST Report (Outward Supplies)"));
            doc.add(meta("GSTIN: " + nn(r.seller().gstin(), "—")
                    + "    State: " + nn(r.seller().state(), "—")
                    + (r.seller().stateCode() != null ? " (" + r.seller().stateCode() + ")" : "")));
            doc.add(meta("Period: " + r.from() + " to " + r.to()));
            if (!r.sellerStateConfigured()) {
                Paragraph w = new Paragraph(
                        "Note: seller state not configured — supplies classified as inter-state (IGST).", WARN);
                w.setSpacingAfter(4f);
                doc.add(w);
            }
            doc.add(gap());

            // GSTR-3B summary
            doc.add(new Paragraph("GSTR-3B Summary", SECTION));
            PdfPTable sum = table(new float[]{2, 2, 2, 2, 2, 2},
                    "Taxable", "CGST", "SGST", "IGST", "Total tax", "Invoice value");
            body(sum, money(r.summary().taxableOutward()), money(r.summary().outputCgst()),
                    money(r.summary().outputSgst()), money(r.summary().outputIgst()),
                    money(r.summary().outputTotal()), money(r.summary().invoiceValue()));
            doc.add(sum);
            doc.add(gap());

            // Rate-wise
            doc.add(new Paragraph("Rate-wise summary", SECTION));
            PdfPTable rate = table(new float[]{1.2f, 2, 2, 2, 2, 2},
                    "Rate %", "Taxable", "CGST", "SGST", "IGST", "Invoice value");
            for (RateWiseRow row : r.rateWise()) {
                body(rate, plain(row.rate()), money(row.taxable()), money(row.cgst()),
                        money(row.sgst()), money(row.igst()), money(row.invoiceValue()));
            }
            emptyRowIf(rate, r.rateWise().isEmpty(), 6);
            doc.add(rate);
            doc.add(gap());

            // HSN-wise
            doc.add(new Paragraph("HSN-wise summary", SECTION));
            PdfPTable hsn = table(new float[]{1.5f, 3, 1.2f, 2, 2, 2, 2},
                    "HSN", "Description", "Qty", "Taxable", "CGST", "SGST", "IGST");
            for (HsnRow row : r.hsn()) {
                body(hsn, nn(row.hsn(), ""), nn(row.description(), ""), plain(row.quantity()),
                        money(row.taxable()), money(row.cgst()), money(row.sgst()), money(row.igst()));
            }
            emptyRowIf(hsn, r.hsn().isEmpty(), 7);
            doc.add(hsn);
            doc.add(gap());

            // State-wise
            doc.add(new Paragraph("State-wise summary (place of supply)", SECTION));
            PdfPTable st = table(new float[]{2.5f, 2, 2, 2, 2, 2},
                    "State", "Type", "Taxable", "CGST", "SGST", "IGST");
            for (StateWiseRow row : r.stateWise()) {
                body(st, nn(row.state(), ""), String.valueOf(row.type()), money(row.taxable()),
                        money(row.cgst()), money(row.sgst()), money(row.igst()));
            }
            emptyRowIf(st, r.stateWise().isEmpty(), 6);
            doc.add(st);

            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "GST_PDF_FAILED",
                    "Could not render the GST report PDF.");
        }
    }

    private static Paragraph meta(String text) {
        Paragraph p = new Paragraph(text, META);
        p.setSpacingBefore(1f);
        return p;
    }

    private static Paragraph gap() {
        Paragraph p = new Paragraph(" ", META);
        p.setSpacingAfter(6f);
        return p;
    }

    private static PdfPTable table(float[] widths, String... headers) {
        PdfPTable t = new PdfPTable(widths.length);
        t.setWidthPercentage(100f);
        try {
            t.setWidths(widths);
        } catch (com.lowagie.text.DocumentException ignored) {
            // Fixed widths are well-formed; ignore.
        }
        t.setSpacingBefore(3f);
        for (String h : headers) {
            PdfPCell c = new PdfPCell(new Phrase(h, TH));
            c.setBackgroundColor(BRAND_GREEN);
            c.setPadding(5f);
            c.setHorizontalAlignment(Element.ALIGN_LEFT);
            t.addCell(c);
        }
        return t;
    }

    private static void body(PdfPTable t, String... cells) {
        // Stateless zebra striping: header is row 0, so data rows alternate from row 1.
        boolean tint = (t.getRows().size() % 2) == 0;
        for (int i = 0; i < cells.length; i++) {
            PdfPCell c = new PdfPCell(new Phrase(cells[i], TD));
            c.setPadding(4f);
            c.setHorizontalAlignment(i == 0 || (cells.length == 7 && i == 1) ? Element.ALIGN_LEFT
                    : Element.ALIGN_RIGHT);
            if (tint) {
                c.setBackgroundColor(ROW_TINT);
            }
            t.addCell(c);
        }
    }

    private static void emptyRowIf(PdfPTable t, boolean empty, int cols) {
        if (!empty) {
            return;
        }
        PdfPCell c = new PdfPCell(new Phrase("No sales in this period.", TD));
        c.setColspan(cols);
        c.setPadding(6f);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        t.addCell(c);
    }

    private static String money(BigDecimal v) {
        return v == null ? "0.00" : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String nn(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
