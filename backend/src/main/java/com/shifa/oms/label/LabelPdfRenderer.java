package com.shifa.oms.label;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shifa.oms.common.ApiException;
import org.springframework.http.HttpStatus;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;

/**
 * Renders {@link InternalLabelContent} models to PDF bytes using OpenPDF, with a
 * scannable Code128 barcode (via {@link BarcodeGenerator}) embedded as an image
 * (design "PDF / label / barcode generation").
 *
 * <p>Rendering is deliberately isolated from content assembly: the builder
 * produces the model and this renderer turns one or many models into a single
 * PDF, one internal-label block per page. Bulk output therefore contains exactly
 * one label block per requested order (Req 10.4, Property 19), which is validated
 * at the content-model level so it never requires parsing PDF bytes.
 */
public class LabelPdfRenderer {

    private final BarcodeGenerator barcodeGenerator;

    // Shipping-label palette + fonts (modelled on the reference courier label).
    private static final Color DARK = new Color(33, 37, 41);
    private static final Color BORDER = new Color(30, 30, 30);
    private static final Color CAPTION_GREY = new Color(110, 110, 110);

    // Fonts sized for an A6 quadrant (four labels to an A4 sheet): compact but
    // still legible after the sheet is cut into four.
    private static final Font HEADER_WHITE = white(FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8));
    private static final Font NAME_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BRAND_DARK = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7);
    private static final Font CAPTION_FONT = grey(FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6));
    private static final Font CODE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
    private static final Font BIG_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);

    private static Font white(Font f) {
        f.setColor(Color.WHITE);
        return f;
    }

    private static Font grey(Font f) {
        f.setColor(CAPTION_GREY);
        return f;
    }

    public LabelPdfRenderer(BarcodeGenerator barcodeGenerator) {
        this.barcodeGenerator = Objects.requireNonNull(barcodeGenerator, "barcodeGenerator");
    }

    /** Renders a single internal label to a one-page PDF. */
    public byte[] render(InternalLabelContent content) {
        return render(List.of(content), null);
    }

    /** Renders a single internal label with an optional company logo. */
    public byte[] render(InternalLabelContent content, byte[] logoPng) {
        return render(List.of(content), logoPng);
    }

    /**
     * Renders one internal-label block per content into a single PDF document,
     * each on its own page, in the given order (Req 10.4).
     *
     * @param contents the label content blocks (non-empty)
     * @return the generated PDF bytes
     */
    public byte[] render(List<InternalLabelContent> contents) {
        return render(contents, null);
    }

    /**
     * Renders one internal-label block per content, embedding the company logo at
     * the top of each block when {@code logoPng} is provided (falls back to the
     * text brand when absent, keeping the layout intact).
     *
     * @param contents the label content blocks (non-empty)
     * @param logoPng  the company logo image bytes, or {@code null} for none
     * @return the generated PDF bytes
     */
    public byte[] render(List<InternalLabelContent> contents, byte[] logoPng) {
        Objects.requireNonNull(contents, "contents");
        if (contents.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_LABELS",
                    "At least one label is required to produce a PDF.");
        }
        // Four labels per A4 sheet, laid out as a 2x2 grid (each label ~A6, an
        // A4 quadrant) — matches how the packing team physically prints and cuts
        // labels four-up. Blocks fill the grid left-to-right, top-to-bottom; a
        // fresh A4 page starts after every fourth label, and a partial final
        // page is padded with empty grid cells so the 2x2 shape is preserved.
        Document document = new Document(PageSize.A4, 18, 18, 18, 18);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            PdfPTable grid = newGrid();
            for (int i = 0; i < contents.size(); i++) {
                if (i > 0 && i % LABELS_PER_PAGE == 0) {
                    // The grid holds a full 2x2 page; flush it and start fresh.
                    document.add(grid);
                    document.newPage();
                    grid = newGrid();
                }
                grid.addCell(quadrantCell(writeLabelBlock(contents.get(i), logoPng)));
            }
            // Pad the last page's grid so any unused quadrants render as empty
            // cells, keeping the 2x2 layout intact, then flush it.
            int placed = contents.size() % LABELS_PER_PAGE;
            if (placed != 0) {
                fillEmptyCells(grid, LABELS_PER_PAGE - placed);
            }
            document.add(grid);
            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "LABEL_PDF_FAILED",
                    "Failed to render the internal company label PDF.");
        }
    }

    private static final int COLUMNS = 2;
    private static final int LABELS_PER_PAGE = 4;

    /** A fresh empty 2-column outer grid spanning the full A4 content width. */
    private PdfPTable newGrid() {
        PdfPTable grid = new PdfPTable(COLUMNS);
        grid.setWidthPercentage(100);
        grid.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        return grid;
    }

    /** Wraps one label block into a padded quadrant cell of the outer A4 grid. */
    private PdfPCell quadrantCell(PdfPTable block) {
        PdfPCell cell = new PdfPCell(block);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6f);
        return cell;
    }

    /** Adds {@code count} empty (borderless) cells to keep the 2x2 grid shape. */
    private void fillEmptyCells(PdfPTable grid, int count) {
        for (int i = 0; i < count; i++) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            grid.addCell(empty);
        }
    }

    /**
     * Renders one label block styled after the reference courier label: a single
     * bordered box divided into stacked sections — a dark "DELIVERY TO" header,
     * the recipient block with the Shifa logo, a scannable Code128 barcode, then
     * a grid of item description/total, order id/payment, ordered-on/COD, the
     * pickup-and-return address and the seller name — all populated from our data.
     */
    private PdfPTable writeLabelBlock(InternalLabelContent content, byte[] logoPng) {
        PdfPTable main = new PdfPTable(1);
        main.setWidthPercentage(100);
        main.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        String brand = nz(content.sellerName(), "Shifa Herbal Remedies");

        // 1) Seller header (letterhead) — brand + address + GST No under it, logo on
        // the right. Mirrors the invoice header so the label reads as the same doc.
        main.addCell(boxWrap(sellerHeaderTable(content, logoPng, brand), 7f));

        // 2) Recipient "To :" block (boxed, full width) — like the invoice To block.
        main.addCell(boxWrap(recipientTable(content), 8f));

        // 3) Single scannable barcode (label redesign feature): when a delivery
        // partner + AWB have been allotted, print ONLY their barcode (name + AWB)
        // so the courier team scans the parcel straight into their own system at
        // pickup — printing our own order barcode alongside it would be a second,
        // redundant barcode on the same label. Our own order code is still
        // resolvable later: the packing/RTO scan flow also recognises a courier
        // AWB and resolves it back to the order (see PackingService's barcode
        // resolution), so scanning the printed courier barcode at RTO still finds
        // the order.
        // Only when no courier/AWB has been allotted yet (e.g. in-house delivery,
        // or a QuikShipX order still awaiting allotment) do we fall back to
        // printing our own order barcode, so there is always exactly one
        // scannable barcode on the label.
        if (content.hasCourierBarcode()) {
            main.addCell(boxWrap(courierBarcodeTable(content), 8f));
        } else {
            main.addCell(boxWrap(orderBarcodeTable(content), 8f));
        }

        // 4) Item description + order total.
        main.addCell(twoColRow(
                "ITEM DESCRIPTION", nz(content.itemSummary(), "\u2014"), Element.ALIGN_LEFT,
                "TOTAL", money(content.totalAmount()), Element.ALIGN_RIGHT,
                3.2f, 1f));

        // 5) Order id + prominent payment badge (Pre-Paid / COD).
        main.addCell(orderPaymentRow(content));

        // 6) Ordered-on + COD-collect amount (or "Prepaid").
        main.addCell(orderedCodRow(content));

        // 7) Pickup & return address (full width).
        main.addCell(captionBox("PICKUP & RETURN ADDRESS", nz(content.pickupReturnAddress(), "\u2014")));

        // 8) Seller name (full width).
        main.addCell(captionBox("SELLER NAME", brand));

        return main;
    }

    // --- Section builders ---------------------------------------------------

    /**
     * Seller letterhead — brand name + address + "GST No : …" under it on the left,
     * the company logo on the right. Mirrors the invoice header (matches the
     * client's approved invoice design).
     */
    private PdfPTable sellerHeaderTable(InternalLabelContent content, byte[] logoPng, String brand) {
        Image logo = logoImage(logoPng);
        PdfPTable t = new PdfPTable(logo != null ? new float[] {3.2f, 1f} : new float[] {1f});
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        PdfPTable left = new PdfPTable(1);
        left.setWidthPercentage(100);
        left.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        left.getDefaultCell().setPaddingBottom(1f);
        left.addCell(new Phrase(brand, BRAND_DARK));
        if (content.sellerAddress() != null && !content.sellerAddress().isBlank()) {
            left.addCell(new Phrase(content.sellerAddress(), SMALL_FONT));
        }
        // GST No directly under the seller address (client requirement, as on the invoice).
        if (content.sellerGstin() != null && !content.sellerGstin().isBlank()) {
            left.addCell(new Phrase("GST No : " + content.sellerGstin(), CODE_FONT));
        }
        PdfPCell lc = new PdfPCell(left);
        lc.setBorder(Rectangle.NO_BORDER);
        t.addCell(lc);

        if (logo != null) {
            logo.scaleToFit(64, 34);
            PdfPCell rc = new PdfPCell(logo, false);
            rc.setBorder(Rectangle.NO_BORDER);
            rc.setHorizontalAlignment(Element.ALIGN_RIGHT);
            rc.setVerticalAlignment(Element.ALIGN_MIDDLE);
            t.addCell(rc);
        }
        return t;
    }

    /** The recipient ("To :") block — name + address + mobile, boxed like the invoice. */
    private PdfPTable recipientTable(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setPaddingBottom(1f);
        t.addCell(new Phrase("To : " + nz(content.customerName(), "\u2014").toUpperCase(), NAME_FONT));
        if (content.addressLine() != null && !content.addressLine().isBlank()) {
            t.addCell(new Phrase(content.addressLine(), SMALL_FONT));
        }
        t.addCell(new Phrase(cityStatePin(content), SMALL_FONT));
        if (content.customerMobile() != null && !content.customerMobile().isBlank()) {
            t.addCell(new Phrase("Mobile : +91 " + content.customerMobile(), SMALL_FONT));
        }
        return t;
    }

    /**
     * The courier barcode block (label redesign feature): the courier partner's
     * display name as a small caption above a Code128 barcode of the allotted
     * AWB, with the AWB digits repeated below for a human read — so the courier
     * team scans this straight into their own system at pickup. Only rendered
     * when {@link InternalLabelContent#hasCourierBarcode()} is {@code true}.
     */
    private PdfPTable courierBarcodeTable(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);

        PdfPCell caption = new PdfPCell(
                new Phrase("COURIER: " + nz(content.courierName(), "\u2014").toUpperCase(), CAPTION_FONT));
        caption.setBorder(Rectangle.NO_BORDER);
        caption.setHorizontalAlignment(Element.ALIGN_CENTER);
        caption.setPaddingBottom(2f);
        t.addCell(caption);

        Image barcode = imageOf(barcodeGenerator.code128Png(content.courierBarcodeValue()));
        barcode.scaleToFit(230, 48);
        PdfPCell bc = new PdfPCell(barcode, false);
        bc.setBorder(Rectangle.NO_BORDER);
        bc.setHorizontalAlignment(Element.ALIGN_CENTER);
        bc.setPadding(3f);
        t.addCell(bc);

        PdfPCell awb = new PdfPCell(new Phrase("AWB: " + content.courierBarcodeValue(), CODE_FONT));
        awb.setBorder(Rectangle.NO_BORDER);
        awb.setHorizontalAlignment(Element.ALIGN_CENTER);
        awb.setPaddingTop(2f);
        t.addCell(awb);
        return t;
    }

    /**
     * The order barcode block (label redesign feature): always present, a
     * Code128 barcode of our own {@code orderCode} — so the godown/RTO flow can
     * scan a returned parcel straight back to the order regardless of whether a
     * courier/AWB was ever allotted.
     */
    private PdfPTable orderBarcodeTable(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);

        Image barcode = imageOf(barcodeGenerator.code128Png(content.orderCode()));
        barcode.scaleToFit(230, 48);
        PdfPCell bc = new PdfPCell(barcode, false);
        bc.setBorder(Rectangle.NO_BORDER);
        bc.setHorizontalAlignment(Element.ALIGN_CENTER);
        bc.setPadding(3f);
        t.addCell(bc);

        PdfPTable sub = new PdfPTable(new float[] {1f, 1f});
        sub.setWidthPercentage(100);
        PdfPCell dest = new PdfPCell(new Phrase(nz(content.city(), nz(content.postalCode(), "")), SMALL_FONT));
        dest.setBorder(Rectangle.NO_BORDER);
        dest.setHorizontalAlignment(Element.ALIGN_LEFT);
        PdfPCell code = new PdfPCell(new Phrase("ORDER: " + content.orderCode(), CODE_FONT));
        code.setBorder(Rectangle.NO_BORDER);
        code.setHorizontalAlignment(Element.ALIGN_RIGHT);
        sub.addCell(dest);
        sub.addCell(code);
        PdfPCell subWrap = new PdfPCell(sub);
        subWrap.setBorder(Rectangle.NO_BORDER);
        t.addCell(subWrap);
        return t;
    }

    /** A full-width bordered section wrapping a nested table with the given padding. */
    private PdfPCell boxWrap(PdfPTable inner, float padding) {
        PdfPCell c = new PdfPCell(inner);
        c.setBorderColor(BORDER);
        c.setPadding(padding);
        return c;
    }

    /** A full-width section: a small grey caption over a body value. */
    private PdfPCell captionBox(String caption, String value) {
        PdfPTable inner = captionValue(caption, value, Element.ALIGN_LEFT);
        return boxWrap(inner, 6f);
    }

    /** A two-column bordered row with a vertical divider between the columns. */
    private PdfPCell twoColRow(String lCap, String lVal, int lAlign,
                               String rCap, String rVal, int rAlign,
                               float wLeft, float wRight) {
        PdfPTable t = new PdfPTable(new float[] {wLeft, wRight});
        t.setWidthPercentage(100);

        PdfPCell l = new PdfPCell(captionValue(lCap, lVal, lAlign));
        l.setBorder(Rectangle.RIGHT);
        l.setBorderColor(BORDER);
        l.setPadding(6f);
        PdfPCell r = new PdfPCell(captionValue(rCap, rVal, rAlign));
        r.setBorder(Rectangle.NO_BORDER);
        r.setPadding(6f);
        t.addCell(l);
        t.addCell(r);

        PdfPCell wrap = new PdfPCell(t);
        wrap.setBorderColor(BORDER);
        wrap.setPadding(0f);
        return wrap;
    }

    /** Order id (left) + big payment badge (right). */
    private PdfPCell orderPaymentRow(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(new float[] {1.6f, 1f});
        t.setWidthPercentage(100);

        PdfPCell l = new PdfPCell(captionValue("ORDER ID", content.orderCode(), Element.ALIGN_LEFT));
        l.setBorder(Rectangle.RIGHT);
        l.setBorderColor(BORDER);
        l.setPadding(6f);

        PdfPCell r = new PdfPCell(new Phrase(paymentBadge(content), BIG_FONT));
        r.setBorder(Rectangle.NO_BORDER);
        r.setHorizontalAlignment(Element.ALIGN_CENTER);
        r.setVerticalAlignment(Element.ALIGN_MIDDLE);
        r.setPadding(6f);
        t.addCell(l);
        t.addCell(r);

        PdfPCell wrap = new PdfPCell(t);
        wrap.setBorderColor(BORDER);
        wrap.setPadding(0f);
        return wrap;
    }

    /** Ordered-on (left) + COD collect amount / prepaid note (right). */
    private PdfPCell orderedCodRow(InternalLabelContent content) {
        String rightCap;
        String rightVal;
        if (content.codApplicable()) {
            rightCap = "COLLECT ON DELIVERY";
            rightVal = money(content.codAmount());
        } else {
            rightCap = "PAYMENT";
            rightVal = "Prepaid — do not collect";
        }
        return twoColRow(
                "ORDERED ON", nz(content.orderedOn(), "\u2014"), Element.ALIGN_LEFT,
                rightCap, rightVal, Element.ALIGN_LEFT,
                1f, 1.4f);
    }

    // --- Small helpers ------------------------------------------------------

    /** A borderless nested table stacking a grey caption over a body value. */
    private PdfPTable captionValue(String caption, String value, int align) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setHorizontalAlignment(align);
        t.addCell(new Phrase(caption, CAPTION_FONT));
        t.addCell(new Phrase(nz(value, "\u2014"), BODY_FONT));
        return t;
    }

    private String paymentBadge(InternalLabelContent content) {
        String label = nz(content.paymentLabel(), content.codApplicable() ? "COD" : "PREPAID");
        // Present "PREPAID" as the reference's "Pre-Paid" styling.
        return "PREPAID".equalsIgnoreCase(label) ? "Pre-Paid" : label;
    }

    private String cityStatePin(InternalLabelContent content) {
        String state = content.state() != null ? content.state().toUpperCase() : null;
        StringBuilder sb = new StringBuilder();
        appendPart(sb, content.postalCode());
        appendPart(sb, content.city());
        appendPart(sb, state);
        return sb.length() == 0 ? "\u2014" : sb.toString();
    }

    private static void appendPart(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(part.trim());
        }
    }

    private static String money(BigDecimal v) {
        BigDecimal a = v != null ? v : BigDecimal.ZERO;
        return "Rs. " + a.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nz(String v, String fallback) {
        return v != null && !v.isBlank() ? v.trim() : fallback;
    }

    private Image imageOf(byte[] png) {
        try {
            return Image.getInstance(png);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "LABEL_PDF_FAILED",
                    "Failed to embed the barcode image into the label PDF.");
        }
    }

    /** Builds a logo Image from bytes, or {@code null} when absent/undecodable. */
    private Image logoImage(byte[] logoPng) {
        if (logoPng == null || logoPng.length == 0) {
            return null;
        }
        try {
            return Image.getInstance(logoPng);
        } catch (Exception e) {
            return null;
        }
    }
}
