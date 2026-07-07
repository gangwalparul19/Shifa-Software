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
import java.text.DecimalFormat;
import java.util.Objects;

/**
 * Renders an {@link InvoiceContent} model to A4 PDF bytes using OpenPDF
 * ({@code com.lowagie.text}), reusing the same library and streaming approach as
 * the label module's {@link com.shifa.oms.label.LabelPdfRenderer}.
 *
 * <p>Two layouts are produced from the same model:
 * <ul>
 *   <li><strong>Plain invoice</strong> (when {@link InvoiceContent#isTaxInvoice()}
 *       is {@code false}) — title "INVOICE", the static company header, no tax
 *       lines: identical to the pre-GST invoice.</li>
 *   <li><strong>GST tax invoice</strong> (when {@code isTaxInvoice()} is
 *       {@code true}) — title "TAX INVOICE", the seller GSTIN + address/state from
 *       settings in the header, a per-line HSN column, and a GST summary
 *       (Taxable Value, CGST+SGST or IGST, Grand Total).</li>
 * </ul>
 *
 * <p><strong>Currency (₹).</strong> The rupee sign ₹ (U+20B9) is not in the
 * base-14 Helvetica encoding, so this renderer embeds a bundled Unicode TrueType
 * font ({@code /fonts/InvoiceUnicode.ttf}) via
 * {@code BaseFont.createFont(..., IDENTITY_H, EMBEDDED, ...)} and uses it for the
 * money cells/totals. If the bundled font is missing or lacks the glyph, it
 * transparently falls back to the {@code "Rs. "} prefix with Helvetica so
 * amounts always render. The label renderer is intentionally left unchanged.
 */
public class InvoicePdfRenderer {

    private static final Logger log = LoggerFactory.getLogger(InvoicePdfRenderer.class);

    /** Classpath location of the bundled Unicode TTF that includes ₹ (U+20B9). */
    private static final String UNICODE_FONT_RESOURCE = "/fonts/InvoiceUnicode.ttf";
    private static final char RUPEE = '\u20B9';

    /** Static company placeholders for the plain invoice — safe to make configurable later. */
    private static final String COMPANY_NAME = "Shifa Herbal Remedies";
    private static final String COMPANY_TAGLINE = "Pure Herbal Wellness, Naturally";
    private static final String COMPANY_CONTACT =
            "Shop 14, Herbal Market, Pune, Maharashtra 411001  |  +91 9302590767  |  care@shifaherbal.example";

    // --- Embedded Unicode font (loaded once) --------------------------------

    private static final BaseFont UNICODE_BASE_FONT = loadUnicodeFont();
    private static final boolean RUPEE_AVAILABLE =
            UNICODE_BASE_FONT != null && UNICODE_BASE_FONT.charExists(RUPEE);

    /** Currency prefix: the ₹ glyph when the embedded font supports it, else "Rs. ". */
    private static final String CURRENCY = RUPEE_AVAILABLE ? (RUPEE + " ") : "Rs. ";

    // --- Shifa Herbal Remedies brand palette (java.awt.Color for OpenPDF) ---
    /** Primary deep herbal green {@code #1F7A4D}. */
    private static final Color BRAND_GREEN = new Color(0x1F, 0x7A, 0x4D);
    /** Dark green for text/emphasis {@code #14532D}. */
    private static final Color DARK_GREEN = new Color(0x14, 0x53, 0x2D);
    /** Gold accent {@code #C8A24A}. */
    private static final Color GOLD = new Color(0xC8, 0xA2, 0x4A);
    /** Light green zebra row tint {@code #EAF3EC}. */
    private static final Color ROW_TINT = new Color(0xEA, 0xF3, 0xEC);
    /** Soft gold tint for highlighted totals / COD box. */
    private static final Color GOLD_TINT = new Color(0xF5, 0xEC, 0xD2);
    /** Header text on green: white. */
    private static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    /** Body text {@code #243B30}. */
    private static final Color BODY_COLOR = new Color(0x24, 0x3B, 0x30);
    /** Muted footer {@code #6B7B72}. */
    private static final Color MUTED = new Color(0x6B, 0x7B, 0x72);
    /** Thin light-gray table border. */
    private static final Color BORDER_GRAY = new Color(0xD7, 0xE1, 0xDA);

    private static final Font COMPANY_FONT = new Font(Font.HELVETICA, 18, Font.BOLD, DARK_GREEN);
    private static final Font TAGLINE_FONT = new Font(Font.HELVETICA, 10, Font.ITALIC, GOLD);
    private static final Font CONTACT_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, BODY_COLOR);
    private static final Font INVOICE_TITLE_FONT = new Font(Font.HELVETICA, 20, Font.BOLD, WHITE);
    private static final Font META_LABEL_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, DARK_GREEN);
    private static final Font HEADING_FONT = new Font(Font.HELVETICA, 11, Font.BOLD, BRAND_GREEN);
    private static final Font BODY_FONT = new Font(Font.HELVETICA, 10, Font.NORMAL, BODY_COLOR);
    private static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, WHITE);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    private static final Font HIGHLIGHT_LABEL_FONT = new Font(Font.HELVETICA, 11, Font.BOLD, DARK_GREEN);
    private static final String CREDIT_LINE =
            "Designed & Developed by Weblithic — https://www.weblithic.com/";

    /** Money fonts: use the embedded Unicode font when ₹ is available, else Helvetica. */
    private static final Font MONEY_FONT = moneyFont(10, false);
    private static final Font MONEY_BOLD_FONT = moneyFont(10, true);
    private static final Font MONEY_COD_FONT = moneyFont(13, true);

    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    /**
     * Renders a single invoice to a one-page (or multi-page for long item lists)
     * A4 PDF.
     *
     * @param content the invoice content model (never {@code null})
     * @return the generated PDF bytes (always starting with {@code %PDF-})
     */
    public byte[] render(InvoiceContent content) {
        return render(content, null);
    }

    /**
     * Renders a single invoice, embedding the given company logo (PNG/JPEG bytes)
     * at the top of the header when provided; when {@code logoPng} is {@code null}
     * the header falls back to the text brand, keeping the layout intact.
     *
     * @param content the invoice content model (never {@code null})
     * @param logoPng the company logo image bytes, or {@code null} for none
     * @return the generated PDF bytes (always starting with {@code %PDF-})
     */
    public byte[] render(InvoiceContent content, byte[] logoPng) {
        Objects.requireNonNull(content, "content");

        Document document = new Document(PageSize.A4, 40, 40, 40, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            writeHeader(document, content, logoPng);
            writeBillToAndMeta(document, content);
            document.add(spacer(6f));
            writeLineItems(document, content);
            writeTotals(document, content);
            writeBankAndTerms(document, content);
            writeFooter(document, content);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INVOICE_PDF_FAILED",
                    "Failed to render the invoice PDF.");
        }
    }

    private void writeHeader(Document document, InvoiceContent content, byte[] logoPng)
            throws DocumentException {
        PdfPTable header = new PdfPTable(new float[] {6f, 4f});
        header.setWidthPercentage(100);

        // Left: company identity.
        PdfPCell company = new PdfPCell();
        company.setBorder(PdfPCell.NO_BORDER);
        // Company logo at the top when configured; falls back to text brand below.
        Image logo = logoImage(logoPng);
        if (logo != null) {
            logo.scaleToFit(160, 70);
            company.addElement(logo);
        }
        if (content.isTaxInvoice()) {
            InvoiceGstDetails gst = content.gst();
            company.addElement(new Paragraph(orDefault(gst.legalName(), COMPANY_NAME), COMPANY_FONT));
            String address = gstSellerAddress(gst);
            if (!address.isBlank()) {
                company.addElement(new Paragraph(address, CONTACT_FONT));
            }
            if (gst.gstin() != null && !gst.gstin().isBlank()) {
                company.addElement(new Paragraph("GSTIN: " + gst.gstin(), META_LABEL_FONT));
            }
            String contact = gstSellerContact(gst);
            if (!contact.isBlank()) {
                company.addElement(new Paragraph(contact, CONTACT_FONT));
            }
        } else {
            company.addElement(new Paragraph(COMPANY_NAME, COMPANY_FONT));
            company.addElement(new Paragraph(COMPANY_TAGLINE, TAGLINE_FONT));
            company.addElement(new Paragraph(COMPANY_CONTACT, CONTACT_FONT));
        }
        header.addCell(company);

        // Right: the title (INVOICE / TAX INVOICE) on a filled green band + number + date.
        PdfPCell invoice = new PdfPCell();
        invoice.setBorder(PdfPCell.NO_BORDER);
        invoice.setHorizontalAlignment(Element.ALIGN_RIGHT);

        // Title in white on a filled green cell for a strong branded accent.
        PdfPTable titleBand = new PdfPTable(1);
        titleBand.setWidthPercentage(100);
        PdfPCell titleCell = new PdfPCell(
                new Phrase(content.isTaxInvoice() ? "TAX INVOICE" : "INVOICE", INVOICE_TITLE_FONT));
        titleCell.setBackgroundColor(BRAND_GREEN);
        titleCell.setBorder(PdfPCell.NO_BORDER);
        titleCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        titleCell.setPadding(6f);
        titleBand.addCell(titleCell);
        invoice.addElement(titleBand);

        Paragraph number = new Paragraph("Invoice No: " + content.invoiceNumber(), BODY_FONT);
        number.setAlignment(Element.ALIGN_RIGHT);
        number.setSpacingBefore(4f);
        invoice.addElement(number);
        Paragraph date = new Paragraph("Invoice Date: " + content.invoiceDate(), BODY_FONT);
        date.setAlignment(Element.ALIGN_RIGHT);
        invoice.addElement(date);
        header.addCell(invoice);

        document.add(header);
        document.add(divider());
    }

    private void writeBillToAndMeta(Document document, InvoiceContent content) throws DocumentException {
        PdfPTable block = new PdfPTable(new float[] {6f, 4f});
        block.setWidthPercentage(100);
        block.setSpacingBefore(8f);

        // Left: Bill To.
        PdfPCell billTo = new PdfPCell();
        billTo.setBorder(PdfPCell.NO_BORDER);
        billTo.addElement(new Paragraph("Bill To", HEADING_FONT));
        billTo.addElement(new Paragraph(content.customerName(), BODY_FONT));
        billTo.addElement(new Paragraph(content.fullAddress(), BODY_FONT));
        billTo.addElement(new Paragraph("Mobile: " + content.customerMobile(), BODY_FONT));
        block.addCell(billTo);

        // Right: order meta.
        PdfPCell meta = new PdfPCell();
        meta.setBorder(PdfPCell.NO_BORDER);
        meta.addElement(new Paragraph("Order Details", HEADING_FONT));
        meta.addElement(metaLine("Order Status: ", content.orderStatus()));
        meta.addElement(metaLine("Payment Status: ", content.paymentStatus()));
        meta.addElement(metaLine("Order Source: ", content.orderSource()));
        if (content.isTaxInvoice()) {
            // Place of supply drives the intra/inter-state GST split.
            meta.addElement(metaLine("Place of Supply: ", content.state()));
        }
        block.addCell(meta);

        document.add(block);
    }

    private Paragraph metaLine(String label, String value) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(label, META_LABEL_FONT));
        p.add(new Phrase(value, BODY_FONT));
        return p;
    }

    private void writeLineItems(Document document, InvoiceContent content) throws DocumentException {
        boolean tax = content.isTaxInvoice();
        PdfPTable table = tax
                ? new PdfPTable(new float[] {0.7f, 4.3f, 1.5f, 1.1f, 2f, 2f})
                : new PdfPTable(new float[] {0.8f, 5f, 1.2f, 2f, 2f});
        table.setWidthPercentage(100);
        table.setSpacingBefore(6f);
        table.setHeaderRows(1);

        table.addCell(headerCell("#", Element.ALIGN_CENTER));
        table.addCell(headerCell("Item", Element.ALIGN_LEFT));
        if (tax) {
            table.addCell(headerCell("HSN", Element.ALIGN_CENTER));
        }
        table.addCell(headerCell("Qty", Element.ALIGN_CENTER));
        table.addCell(headerCell("Rate", Element.ALIGN_RIGHT));
        table.addCell(headerCell("Amount", Element.ALIGN_RIGHT));

        int rowIndex = 0;
        for (InvoiceContent.InvoiceLineItem item : content.lineItems()) {
            boolean even = rowIndex % 2 == 0;
            table.addCell(bodyCell(Integer.toString(item.position()), Element.ALIGN_CENTER, even));
            table.addCell(bodyCell(item.productName(), Element.ALIGN_LEFT, even));
            if (tax) {
                String hsn = item.hsnCode() != null ? item.hsnCode() : "";
                table.addCell(bodyCell(hsn, Element.ALIGN_CENTER, even));
            }
            table.addCell(bodyCell(Integer.toString(item.quantity()), Element.ALIGN_CENTER, even));
            table.addCell(moneyCell(money(item.rate()), Element.ALIGN_RIGHT, even));
            table.addCell(moneyCell(money(item.amount()), Element.ALIGN_RIGHT, even));
            rowIndex++;
        }
        document.add(table);
    }

    private void writeTotals(Document document, InvoiceContent content) throws DocumentException {
        // Right-aligned totals block: a nested two-column table pushed to the right.
        PdfPTable wrapper = new PdfPTable(new float[] {4f, 6f});
        wrapper.setWidthPercentage(100);
        wrapper.setSpacingBefore(10f);

        PdfPCell blank = new PdfPCell();
        blank.setBorder(PdfPCell.NO_BORDER);
        wrapper.addCell(blank);

        PdfPTable totals = new PdfPTable(new float[] {3f, 2f});
        totals.setWidthPercentage(100);

        if (content.isTaxInvoice()) {
            GstComputation gst = content.gst().computation();
            // Show the pre-discount subtotal + discount when a coupon was applied.
            if (content.hasDiscount()) {
                totals.addCell(totalsLabel("Subtotal"));
                totals.addCell(totalsMoneyValue(money(content.subtotal())));
                totals.addCell(totalsLabel(discountLabel(content)));
                totals.addCell(totalsMoneyValue("- " + money(content.discountAmount())));
            }
            totals.addCell(totalsLabel("Taxable Value"));
            totals.addCell(totalsMoneyValue(money(gst.taxableValue())));
            if (gst.intraState()) {
                totals.addCell(totalsLabel("CGST @ " + rate(gst.cgstRate()) + "%"));
                totals.addCell(totalsMoneyValue(money(gst.cgstAmount())));
                totals.addCell(totalsLabel("SGST @ " + rate(gst.sgstRate()) + "%"));
                totals.addCell(totalsMoneyValue(money(gst.sgstAmount())));
            } else {
                totals.addCell(totalsLabel("IGST @ " + rate(gst.igstRate()) + "%"));
                totals.addCell(totalsMoneyValue(money(gst.igstAmount())));
            }
            // Grand Total is the headline figure on a tax invoice — highlight it.
            totals.addCell(totalsLabelHighlight("Grand Total"));
            totals.addCell(totalsMoneyValueHighlight(money(gst.grandTotal())));
        } else {
            totals.addCell(totalsLabel("Subtotal"));
            totals.addCell(totalsMoneyValue(money(content.subtotal())));
            // Discount row + net total when a coupon was applied (Phase D).
            if (content.hasDiscount()) {
                totals.addCell(totalsLabel(discountLabel(content)));
                totals.addCell(totalsMoneyValue("- " + money(content.discountAmount())));
                totals.addCell(totalsLabelHighlight("Total"));
                totals.addCell(totalsMoneyValueHighlight(money(content.netTotal())));
            }
        }

        totals.addCell(totalsLabel("Amount Received"));
        totals.addCell(totalsMoneyValue(money(content.amountReceived())));
        // Balance is the headline figure when there is no highlighted total above it.
        boolean highlightBalance = !content.isTaxInvoice() && !content.hasDiscount();
        if (highlightBalance) {
            totals.addCell(totalsLabelHighlight("Balance"));
            totals.addCell(totalsMoneyValueHighlight(money(content.balanceDue())));
        } else {
            totals.addCell(totalsLabel("Balance"));
            totals.addCell(totalsMoneyValue(money(content.balanceDue())));
        }
        totals.addCell(totalsLabel("Payment Status"));
        totals.addCell(totalsValue(content.paymentStatus()));

        PdfPCell totalsCell = new PdfPCell(totals);
        totalsCell.setBorder(PdfPCell.NO_BORDER);
        wrapper.addCell(totalsCell);

        document.add(wrapper);

        // Prominent COD / Amount Due on Delivery box when applicable.
        if (content.codApplicable()) {
            BigDecimal due = content.amountDueOnDelivery() != null
                    ? content.amountDueOnDelivery() : BigDecimal.ZERO;
            PdfPTable codBox = new PdfPTable(1);
            codBox.setWidthPercentage(100);
            codBox.setSpacingBefore(8f);
            PdfPCell codCell = new PdfPCell(
                    new Phrase("Amount Due on Delivery (COD): " + money(due), MONEY_COD_FONT));
            codCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            codCell.setPadding(8f);
            codCell.setBackgroundColor(GOLD_TINT);
            codCell.setBorderColor(GOLD);
            codCell.setBorderWidth(1.2f);
            codBox.addCell(codCell);
            document.add(codBox);
        }
    }

    /**
     * Renders the optional bank-details block and the terms &amp; conditions
     * section from settings (Wave 3, Feature 1). Both are shown on plain and tax
     * invoices when present; nothing is emitted when neither is configured.
     */
    private void writeBankAndTerms(Document document, InvoiceContent content) throws DocumentException {
        if (content.hasBankDetails()) {
            document.add(divider());
            Paragraph heading = new Paragraph("Bank Details", HEADING_FONT);
            heading.setSpacingBefore(8f);
            document.add(heading);
            InvoiceContent.BankDetails bank = content.bankDetails();
            addBankLine(document, "Bank: ", bank.bankName());
            addBankLine(document, "Account Name: ", bank.accountName());
            addBankLine(document, "Account No: ", bank.accountNumber());
            addBankLine(document, "IFSC: ", bank.ifsc());
            addBankLine(document, "Branch: ", bank.branch());
        }
        if (content.hasTerms()) {
            Paragraph heading = new Paragraph("Terms & Conditions", HEADING_FONT);
            heading.setSpacingBefore(10f);
            document.add(heading);
            // Preserve author line breaks in the multi-line terms text.
            for (String line : content.invoiceTerms().split("\\r?\\n")) {
                Paragraph p = new Paragraph(line.isBlank() ? " " : line, FOOTER_FONT);
                document.add(p);
            }
        }
    }

    private void addBankLine(Document document, String label, String value) throws DocumentException {
        if (value == null || value.isBlank()) {
            return;
        }
        Paragraph p = new Paragraph();
        p.add(new Phrase(label, META_LABEL_FONT));
        p.add(new Phrase(value, BODY_FONT));
        document.add(p);
    }

    private void writeFooter(Document document, InvoiceContent content) throws DocumentException {
        document.add(divider());
        if (content.isTaxInvoice()) {
            String note = content.gst().footerNote();
            if (note != null && !note.isBlank()) {
                Paragraph footerNote = new Paragraph(note, FOOTER_FONT);
                footerNote.setAlignment(Element.ALIGN_CENTER);
                footerNote.setSpacingBefore(10f);
                document.add(footerNote);
            }
        }
        Paragraph thanks = new Paragraph("Thank you for shopping with Shifa Herbal Remedies!", FOOTER_FONT);
        thanks.setAlignment(Element.ALIGN_CENTER);
        thanks.setSpacingBefore(content.isTaxInvoice() ? 4f : 10f);
        document.add(thanks);

        Paragraph generated = new Paragraph("This is a computer-generated invoice.", FOOTER_FONT);
        generated.setAlignment(Element.ALIGN_CENTER);
        document.add(generated);

        Paragraph credit = new Paragraph(CREDIT_LINE, new Font(Font.HELVETICA, 7, Font.NORMAL, MUTED));
        credit.setAlignment(Element.ALIGN_CENTER);
        credit.setSpacingBefore(2f);
        document.add(credit);
    }

    // --- Cell / formatting helpers -----------------------------------------

    private PdfPCell headerCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, TABLE_HEADER_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        cell.setBackgroundColor(BRAND_GREEN);
        cell.setBorderColor(BRAND_GREEN);
        return cell;
    }

    private PdfPCell bodyCell(String text, int alignment, boolean even) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        cell.setBackgroundColor(even ? WHITE : ROW_TINT);
        cell.setBorderColor(BORDER_GRAY);
        cell.setBorderWidth(0.5f);
        return cell;
    }

    /** A body cell whose text may contain the ₹ glyph, so it uses the money font. */
    private PdfPCell moneyCell(String text, int alignment, boolean even) {
        PdfPCell cell = new PdfPCell(new Phrase(text, MONEY_FONT));
        cell.setHorizontalAlignment(alignment);
        cell.setPadding(5f);
        cell.setBackgroundColor(even ? WHITE : ROW_TINT);
        cell.setBorderColor(BORDER_GRAY);
        cell.setBorderWidth(0.5f);
        return cell;
    }

    private PdfPCell totalsLabel(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPadding(3f);
        return cell;
    }

    private PdfPCell totalsValue(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, MONEY_BOLD_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPadding(3f);
        return cell;
    }

    /** A right-aligned totals value that may contain ₹, so it uses the bold money font. */
    private PdfPCell totalsMoneyValue(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, MONEY_BOLD_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setBorder(PdfPCell.NO_BORDER);
        cell.setPadding(3f);
        return cell;
    }

    /** A highlighted (gold-tinted) headline totals label — for the primary total line. */
    private PdfPCell totalsLabelHighlight(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HIGHLIGHT_LABEL_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setBackgroundColor(GOLD_TINT);
        cell.setBorderColor(GOLD);
        cell.setBorderWidth(0.8f);
        cell.setPadding(5f);
        return cell;
    }

    /** A highlighted (gold-tinted) headline totals value that may contain ₹. */
    private PdfPCell totalsMoneyValueHighlight(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, MONEY_BOLD_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setBackgroundColor(GOLD_TINT);
        cell.setBorderColor(GOLD);
        cell.setBorderWidth(0.8f);
        cell.setPadding(5f);
        return cell;
    }

    /** The discount totals-row label, including the coupon code when present. */
    private String discountLabel(InvoiceContent content) {
        String code = content.couponCode();
        return (code != null && !code.isBlank()) ? "Discount (" + code + ")" : "Discount";
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

    private String gstSellerAddress(InvoiceGstDetails gst) {
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

    private String gstSellerContact(InvoiceGstDetails gst) {
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

    private Paragraph spacer(float after) {
        Paragraph p = new Paragraph(" ", BODY_FONT);
        p.setSpacingAfter(after);
        return p;
    }

    /**
     * A thin green ruled line rendered as a full-width single-cell table with a
     * colored bottom border — a real brand divider without raw canvas graphics.
     */
    private PdfPTable divider() {
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingBefore(4f);
        rule.setSpacingAfter(2f);
        PdfPCell cell = new PdfPCell(new Phrase(" ", FOOTER_FONT));
        cell.setFixedHeight(1.4f);
        cell.setBorder(PdfPCell.BOTTOM);
        cell.setBorderColorBottom(BRAND_GREEN);
        cell.setBorderWidthBottom(1.2f);
        rule.addCell(cell);
        return rule;
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
