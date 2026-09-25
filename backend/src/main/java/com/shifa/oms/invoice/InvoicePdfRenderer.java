package com.shifa.oms.invoice;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shifa.oms.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Objects;

/**
 * Renders an {@link InvoiceContent} model to A4 PDF bytes using OpenPDF
 * ({@code com.lowagie.text}).
 *
 * <p>The layout is a compact, fully-bordered "boxed" tax-invoice matching the
 * client's approved design:
 * <ul>
 *   <li><strong>Header</strong> — seller identity (legal name, address, then the
 *       <em>GST No directly under the address</em>, then contact) on the left, and
 *       a bordered meta box (Invoice No / Date / Mode / COD Amt) on the right.</li>
 *   <li><strong>To</strong> — the customer name + full address, full width, with
 *       <em>no</em> GST/State/HSN box.</li>
 *   <li><strong>Items</strong> — Product Name / HSN / SL Price / QTY / Discount /
 *       Inc.GST / Net Amount, then an ID + Order AMT + Total row.</li>
 *   <li><strong>Amount in words</strong> — the grand total spelled out.</li>
 *   <li><strong>GST breakup</strong> — Taxable Value / CGST / SGST / IGST / Tax
 *       Value (from the aggregate {@link GstComputation}).</li>
 *   <li><strong>Footer</strong> — "Thank You For Choosing Shifa Herbal" only, with
 *       <em>no</em> GST No line.</li>
 * </ul>
 *
 * <p>A plain (non-GST) invoice renders the same frame without the Inc.GST column
 * and GST breakup table.
 *
 * <p><strong>Currency (₹).</strong> The rupee sign ₹ (U+20B9) is not in the
 * base-14 Helvetica encoding, so this renderer embeds a bundled Unicode TrueType
 * font ({@code /fonts/InvoiceUnicode.ttf}) and uses it for the money cells; if
 * the bundled font is missing it falls back to the {@code "Rs. "} prefix.
 */
public class InvoicePdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfRenderer.class);

    /** Classpath location of the bundled Unicode TTF that includes ₹ (U+20B9). */
    private static final String UNICODE_FONT_RESOURCE = "/fonts/InvoiceUnicode.ttf";
    private static final char RUPEE = '\u20B9';

    /** Static company placeholders for the plain invoice — safe to make configurable later. */
    private static final String COMPANY_NAME = "Shifa Herbal Remedies";
    private static final String COMPANY_CONTACT =
            "Shop 14, Herbal Market, Pune, Maharashtra 411001  |  +91 9302590767  |  care@shifaherbal.example";

    // --- Embedded Unicode font (loaded once) --------------------------------

    private static final BaseFont UNICODE_BASE_FONT = loadUnicodeFont();
    private static final boolean RUPEE_AVAILABLE =
            UNICODE_BASE_FONT != null && UNICODE_BASE_FONT.charExists(RUPEE);

    /** Currency prefix: the ₹ glyph when the embedded font supports it, else "Rs. ". */
    private static final String CURRENCY = RUPEE_AVAILABLE ? (RUPEE + " ") : "Rs. ";

    // --- Palette (kept subtle; the boxed layout uses mostly black rules) -----
    private static final Color BLACK = new Color(0x00, 0x00, 0x00);
    private static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    /** Light grey header-row tint for table headers. */
    private static final Color HEAD_TINT = new Color(0xEC, 0xEC, 0xEC);
    private static final Color BRAND_GREEN = new Color(0x1F, 0x7A, 0x4D);
    private static final Color MUTED = new Color(0x6B, 0x6B, 0x6B);

    private static final Font COMPANY_FONT = new Font(Font.HELVETICA, 17, Font.BOLD, BLACK);
    private static final Font ADDRESS_FONT = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, BLACK);
    private static final Font GST_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, BLACK);
    private static final Font CONTACT_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font META_LABEL_FONT = new Font(Font.HELVETICA, 9.5f, Font.BOLD, BLACK);
    private static final Font META_VALUE_FONT = new Font(Font.HELVETICA, 9.5f, Font.BOLD, BLACK);
    private static final Font TO_NAME_FONT = new Font(Font.HELVETICA, 12, Font.BOLD, BLACK);
    private static final Font BODY_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, BLACK);
    private static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, BLACK);
    private static final Font BOLD_BODY_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, BLACK);
    private static final Font WORDS_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, BLACK);
    private static final Font THANKS_FONT = new Font(Font.HELVETICA, 12, Font.BOLDITALIC, BRAND_GREEN);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final String CREDIT_LINE =
            "Designed & Developed by Weblithic — https://www.weblithic.com/";

    /** Money fonts: use the embedded Unicode font when ₹ is available, else Helvetica. */
    private static final Font MONEY_FONT = moneyFont(9, false);
    private static final Font MONEY_BOLD_FONT = moneyFont(9, true);

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public byte[] render(InvoiceContent content) {
        return render(content, null);
    }

    public byte[] render(InvoiceContent content, byte[] logoPng) {
        Objects.requireNonNull(content, "content");

        Document document = new Document(PageSize.A4, 26, 26, 26, 26);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            // One outer table = the invoice frame. Every section is a full-width row
            // so the black borders line up into the boxed design.
            writeHeader(document, content, logoPng);
            writeBillTo(document, content);
            writeLineItems(document, content);
            writeIdTotalRow(document, content);
            writeAmountInWords(document, content);
            if (content.isTaxInvoice()) {
                writeGstBreakup(document, content);
            }
            writeFooter(document, content);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVOICE_PDF_FAILED",
                    "Failed to render the invoice PDF.");
        }
    }

    // --- Header: seller (left) | meta box (right) ---------------------------

    private void writeHeader(Document document, InvoiceContent content, byte[] logoPng)
            throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] {6.2f, 3.8f});
        header.setWidthPercentage(100);

        String legalName;
        String address;
        String gstin;
        String contact;
        if (content.isTaxInvoice()) {
            InvoiceGstDetails gst = content.gst();
            legalName = orDefault(gst.legalName(), COMPANY_NAME);
            address = sellerAddress(gst);
            gstin = gst.gstin();
            contact = sellerContact(gst);
        } else {
            legalName = COMPANY_NAME;
            address = COMPANY_CONTACT;
            gstin = null;
            contact = "";
        }

        // Left cell: seller identity as a letterhead — the text block on the left
        // and the company logo on the right (matches the sample where the logo sits
        // beside the company name). The logo lives in its own cell so it renders
        // reliably regardless of the surrounding text.
        PdfPCell seller = boxedCell();
        seller.setPadding(0f);
        Image logo = logoImage(logoPng);
        PdfPTable letterhead = new PdfPTable(logo != null ? new float[] {3.4f, 1f} : new float[] {1f});
        letterhead.setWidthPercentage(100);

        PdfPCell textCell = new PdfPCell();
        textCell.setBorder(PdfPCell.NO_BORDER);
        textCell.setPadding(7f);
        textCell.addElement(new Paragraph(legalName, COMPANY_FONT));
        if (!address.isBlank()) {
            textCell.addElement(new Paragraph(address, ADDRESS_FONT));
        }
        // GST No goes DIRECTLY BELOW the company address (client requirement).
        if (gstin != null && !gstin.isBlank()) {
            textCell.addElement(new Paragraph("GST No : " + gstin, GST_FONT));
        }
        if (!contact.isBlank()) {
            textCell.addElement(new Paragraph(contact, CONTACT_FONT));
        }
        letterhead.addCell(textCell);

        if (logo != null) {
            logo.scaleToFit(120, 60);
            PdfPCell logoCell = new PdfPCell(logo, false);
            logoCell.setBorder(PdfPCell.NO_BORDER);
            logoCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            logoCell.setPadding(6f);
            letterhead.addCell(logoCell);
        }

        seller.addElement(letterhead);
        header.addCell(seller);

        // Right cell: a 2-column meta box (label | value) with inner borders.
        PdfPCell metaWrap = boxedCell();
        metaWrap.setPadding(0f);
        PdfPTable meta = new PdfPTable(new float[] {1.1f, 1.6f});
        meta.setWidthPercentage(100);
        metaRow(meta, "Invoice No", content.invoiceNumber());
        metaRow(meta, "Date", content.invoiceDate());
        metaRow(meta, "Mode", paymentMode(content));
        metaRow(meta, "COD Amt", money(content.codApplicable()
                ? nz(content.amountDueOnDelivery()) : BigDecimal.ZERO) + " /-");
        metaWrap.addElement(meta);
        header.addCell(metaWrap);

        document.add(header);
    }

    private void metaRow(PdfPTable table, String label, String value) {
        PdfPCell l = new PdfPCell(new Phrase(label, META_LABEL_FONT));
        l.setPadding(5f);
        l.setBorderColor(BLACK);
        l.setBorderWidth(0.7f);
        table.addCell(l);
        PdfPCell v = new PdfPCell(new Phrase(value, META_VALUE_FONT));
        v.setPadding(5f);
        v.setBorderColor(BLACK);
        v.setBorderWidth(0.7f);
        table.addCell(v);
    }

    // --- To (customer) — full width, NO GST/State/HSN box -------------------

    private void writeBillTo(Document document, InvoiceContent content) throws DocumentException {
        PdfPTable block = new PdfPTable(1);
        block.setWidthPercentage(100);
        PdfPCell cell = boxedCell();
        cell.setPadding(7f);
        cell.addElement(new Paragraph("To : " + upper(content.customerName()), TO_NAME_FONT));
        cell.addElement(new Paragraph(content.fullAddress(), BODY_FONT));
        cell.addElement(new Paragraph("Mobile : " + content.customerMobile(), BODY_FONT));
        block.addCell(cell);
        document.add(block);
    }

    // --- Line items ---------------------------------------------------------

    private void writeLineItems(Document document, InvoiceContent content) throws DocumentException {
        boolean tax = content.isTaxInvoice();
        PdfPTable table = tax
                ? new PdfPTable(new float[] {3.0f, 1.2f, 1.5f, 0.8f, 1.2f, 1.5f, 1.6f})
                : new PdfPTable(new float[] {3.6f, 1.4f, 1.6f, 0.9f, 1.6f});
        table.setWidthPercentage(100);
        table.setHeaderRows(1);

        table.addCell(headerCell("Product Name", Element.ALIGN_LEFT));
        table.addCell(headerCell("HSN / SKU", Element.ALIGN_CENTER));
        table.addCell(headerCell("SL Price", Element.ALIGN_RIGHT));
        table.addCell(headerCell("QTY", Element.ALIGN_CENTER));
        table.addCell(headerCell("Discount", Element.ALIGN_RIGHT));
        if (tax) {
            table.addCell(headerCell("Inc.GST" + gstHeaderSuffix(content), Element.ALIGN_RIGHT));
        }
        table.addCell(headerCell("Net Amount", Element.ALIGN_RIGHT));

        for (InvoiceContent.InvoiceLineItem item : content.lineItems()) {
            table.addCell(bodyCell(item.productName(), Element.ALIGN_LEFT));
            String hsn = item.hsnCode() != null && !item.hsnCode().isBlank() ? item.hsnCode() : "-";
            table.addCell(bodyCell(hsn, Element.ALIGN_CENTER));
            table.addCell(moneyCell(money(item.rate()), Element.ALIGN_RIGHT));
            table.addCell(bodyCell(Integer.toString(item.quantity()), Element.ALIGN_CENTER));
            String disc = item.discount() != null && item.discount().signum() > 0
                    ? money(item.discount()) : "-";
            table.addCell(moneyCell(disc, Element.ALIGN_RIGHT));
            if (tax) {
                table.addCell(moneyCell(money(lineGst(item)), Element.ALIGN_RIGHT));
            }
            table.addCell(moneyCell(money(item.amount()), Element.ALIGN_RIGHT));
        }
        document.add(table);
    }

    /** The GST% suffix on the Inc.GST header — "(5%)" for a single rate, blank for mixed. */
    private String gstHeaderSuffix(InvoiceContent content) {
        BigDecimal r = content.gst().computation().ratePercent();
        return (r != null && r.signum() > 0) ? " (" + rate(r) + "%)" : "";
    }

    /** GST contained within a GST-inclusive line amount at the line's own rate. */
    private BigDecimal lineGst(InvoiceContent.InvoiceLineItem item) {
        BigDecimal amount = item.amount() != null ? item.amount() : BigDecimal.ZERO;
        BigDecimal rate = item.gstRatePercent();
        if (rate == null || rate.signum() <= 0 || amount.signum() <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal divisor = BigDecimal.ONE.add(rate.divide(HUNDRED, 6, RoundingMode.HALF_UP));
        BigDecimal net = amount.divide(divisor, 2, RoundingMode.HALF_UP);
        return amount.subtract(net).setScale(2, RoundingMode.HALF_UP);
    }

    // --- ID + Order AMT + Total --------------------------------------------

    private void writeIdTotalRow(Document document, InvoiceContent content) throws DocumentException {
        PdfPTable row = new PdfPTable(new float[] {3.0f, 4.0f, 3.0f});
        row.setWidthPercentage(100);

        PdfPCell id = boxedCell();
        id.setPadding(6f);
        id.addElement(new Paragraph("ID : " + content.invoiceNumber(), BOLD_BODY_FONT));
        row.addCell(id);

        PdfPCell order = boxedCell();
        order.setPadding(6f);
        order.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph orderAmt = new Paragraph("Order AMT : " + money(content.netTotal()), MONEY_FONT);
        orderAmt.setAlignment(Element.ALIGN_CENTER);
        order.addElement(orderAmt);
        row.addCell(order);

        PdfPCell total = boxedCell();
        total.setPadding(6f);
        BigDecimal grand = content.isTaxInvoice()
                ? content.gst().computation().grandTotal() : content.netTotal();
        Paragraph totalP = new Paragraph("Total : " + money(grand), MONEY_BOLD_FONT);
        totalP.setAlignment(Element.ALIGN_RIGHT);
        total.addElement(totalP);
        row.addCell(total);

        document.add(row);
    }

    // --- Amount in words ----------------------------------------------------

    private void writeAmountInWords(Document document, InvoiceContent content) throws DocumentException {
        BigDecimal grand = content.isTaxInvoice()
                ? content.gst().computation().grandTotal() : content.netTotal();
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell cell = boxedCell();
        cell.setPadding(6f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph p = new Paragraph(RupeeWords.toWords(grand), WORDS_FONT);
        p.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p);
        t.addCell(cell);
        document.add(t);
    }

    // --- GST breakup (aggregate) -------------------------------------------

    private void writeGstBreakup(Document document, InvoiceContent content) throws DocumentException {
        GstComputation gst = content.gst().computation();
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);

        table.addCell(headerCell("Taxable Value", Element.ALIGN_CENTER));
        table.addCell(headerCell("CGST (" + rate(gst.cgstRate()) + "%)", Element.ALIGN_CENTER));
        table.addCell(headerCell("SGST (" + rate(gst.sgstRate()) + "%)", Element.ALIGN_CENTER));
        table.addCell(headerCell("IGST (" + rate(gst.igstRate()) + "%)", Element.ALIGN_CENTER));
        table.addCell(headerCell("Tax Value", Element.ALIGN_CENTER));

        table.addCell(moneyCell(money(gst.taxableValue()), Element.ALIGN_CENTER));
        table.addCell(moneyCell(money(gst.cgstAmount()), Element.ALIGN_CENTER));
        table.addCell(moneyCell(money(gst.sgstAmount()), Element.ALIGN_CENTER));
        table.addCell(moneyCell(money(gst.igstAmount()), Element.ALIGN_CENTER));
        table.addCell(moneyCell(money(gst.totalTax()), Element.ALIGN_CENTER));

        document.add(table);
    }

    // --- Footer: thank-you only, NO GST No ---------------------------------

    private void writeFooter(Document document, InvoiceContent content) throws DocumentException {
        Paragraph thanks = new Paragraph("Thank You For Choosing Shifa Herbal", THANKS_FONT);
        thanks.setAlignment(Element.ALIGN_CENTER);
        thanks.setSpacingBefore(12f);
        document.add(thanks);

        Paragraph generated = new Paragraph("This is a computer-generated invoice.", FOOTER_FONT);
        generated.setAlignment(Element.ALIGN_CENTER);
        generated.setSpacingBefore(4f);
        document.add(generated);

        Paragraph credit = new Paragraph(CREDIT_LINE, new Font(Font.HELVETICA, 7, Font.NORMAL, MUTED));
        credit.setAlignment(Element.ALIGN_CENTER);
        credit.setSpacingBefore(2f);
        document.add(credit);
    }

    // --- Cell / formatting helpers -----------------------------------------

    /** A borderless-content cell inside the boxed frame: full black 0.9pt border. */
    private PdfPCell boxedCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BLACK);
        cell.setBorderWidth(0.9f);
        return cell;
    }

    private PdfPCell headerCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEADER_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        cell.setBackgroundColor(HEAD_TINT);
        cell.setBorderColor(BLACK);
        cell.setBorderWidth(0.7f);
        return cell;
    }

    private PdfPCell bodyCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        cell.setBackgroundColor(WHITE);
        cell.setBorderColor(BLACK);
        cell.setBorderWidth(0.6f);
        return cell;
    }

    /** A body cell whose text may contain the ₹ glyph, so it uses the money font. */
    private PdfPCell moneyCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, MONEY_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        cell.setBackgroundColor(WHITE);
        cell.setBorderColor(BLACK);
        cell.setBorderWidth(0.6f);
        return cell;
    }

    /** Formats money as {@code "₹ 1,234.50"} (or {@code "Rs. 1,234.50"} fallback), 2 decimals. */
    private String money(BigDecimal value) {
        BigDecimal safe = value != null ? value : BigDecimal.ZERO;
        return CURRENCY + MONEY.format(safe);
    }

    /** Formats a GST rate percent without trailing zeros (e.g. 2.5, 5, 18). */
    private String rate(BigDecimal value) {
        BigDecimal safe = value != null ? value : BigDecimal.ZERO;
        return safe.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** "PREPAID" when nothing is due on delivery, else "COD". */
    private String paymentMode(InvoiceContent content) {
        return content.codApplicable() ? "COD" : "PREPAID";
    }

    private String sellerAddress(InvoiceGstDetails gst) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, gst.addressLine());
        appendPart(sb, gst.city());
        String stateAndCode = gst.state() != null ? gst.state() : "";
        if (gst.stateCode() != null && !gst.stateCode().isBlank()) {
            stateAndCode = (stateAndCode.isBlank() ? "" : stateAndCode + " ") + "(" + gst.stateCode() + ")";
        }
        appendPart(sb, stateAndCode);
        return sb.toString();
    }

    private String sellerContact(InvoiceGstDetails gst) {
        StringBuilder sb = new StringBuilder();
        if (gst.contactPhone() != null && !gst.contactPhone().isBlank()) {
            sb.append(gst.contactPhone());
        }
        if (gst.contactEmail() != null && !gst.contactEmail().isBlank()) {
            if (sb.length() > 0) {
                sb.append("  |  ");
            }
            sb.append(gst.contactEmail());
        }
        return sb.toString();
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.trim());
        }
    }

    private String orDefault(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String upper(String v) {
        return v == null ? "" : v.toUpperCase();
    }

    /** Builds a logo Image from bytes, or {@code null} when absent/undecodable. */
    private Image logoImage(byte[] logoPng) {
        if (logoPng == null || logoPng.length == 0) {
            return null;
        }
        try {
            return Image.getInstance(logoPng);
        } catch (Exception e) {
            log.warn("Failed to embed company logo into the invoice PDF; using text brand.");
            return null;
        }
    }

    // --- Embedded font loading ---------------------------------------------

    private static BaseFont loadUnicodeFont() {
        try (InputStream in = InvoicePdfRenderer.class.getResourceAsStream(UNICODE_FONT_RESOURCE)) {
            if (in == null) {
                log.warn("Bundled invoice Unicode font {} not found; falling back to 'Rs.' currency prefix.",
                        UNICODE_FONT_RESOURCE);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return BaseFont.createFont("InvoiceUnicode.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED,
                    BaseFont.CACHED, bytes, null);
        } catch (Exception e) {
            log.warn("Failed to load bundled invoice Unicode font; falling back to 'Rs.' currency prefix.", e);
            return null;
        }
    }

    private static Font moneyFont(float size, boolean bold) {
        if (RUPEE_AVAILABLE) {
            return new Font(UNICODE_BASE_FONT, size, bold ? Font.BOLD : Font.NORMAL);
        }
        return FontFactory.getFont(bold ? FontFactory.HELVETICA_BOLD : FontFactory.HELVETICA, size);
    }
}
