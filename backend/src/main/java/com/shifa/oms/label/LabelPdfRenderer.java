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

    // Bundled brand logo (fallback when no Settings logo is uploaded), decoded lazily once.
    private Image bundledLogo;
    private boolean bundledLogoLoaded;

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

    // A4 is 595x842pt. With 18pt margins the printable area is ~559x806pt, so a
    // 2x2 grid gives quadrants of ~279x403pt. We pin each quadrant to a FIXED
    // height so the two rows are uniform and always fit on ONE page — otherwise a
    // taller label (long address) grows its row and pushes the second row (and
    // labels 3-4) onto a following page, which is exactly the "goes to next page"
    // bug. The height is set a touch under half the printable height for safety.
    private static final float QUADRANT_HEIGHT = 396f;

    // Minimum height of the item-description row so it absorbs the quadrant's
    // leftover vertical space (the other sections are compact), pushing the
    // pickup address to the bottom and giving the product list room to grow.
    private static final float ITEM_ROW_MIN_HEIGHT = 110f;

    /** A fresh empty 2-column outer grid spanning the full A4 content width. */
    private PdfPTable newGrid() {
        PdfPTable grid = new PdfPTable(COLUMNS);
        grid.setWidthPercentage(100);
        // Keep each 2-cell row intact on one page; never split a row across pages.
        grid.setSplitLate(false);
        grid.setSplitRows(false);
        grid.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        return grid;
    }

    /**
     * Wraps one label block into a fixed-height, top-aligned quadrant cell so all
     * four cells are the same size and exactly two rows fit on one A4 page.
     */
    private PdfPCell quadrantCell(PdfPTable block) {
        PdfPCell cell = new PdfPCell(block);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(6f);
        cell.setFixedHeight(QUADRANT_HEIGHT);
        cell.setVerticalAlignment(Element.ALIGN_TOP);
        return cell;
    }

    /** Adds {@code count} empty (borderless) fixed-height cells to hold the 2x2 shape. */
    private void fillEmptyCells(PdfPTable grid, int count) {
        for (int i = 0; i < count; i++) {
            PdfPCell empty = new PdfPCell();
            empty.setBorder(Rectangle.NO_BORDER);
            empty.setFixedHeight(QUADRANT_HEIGHT);
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

        // 3) Compact barcode + info row (space-saving redesign): a SQUARE barcode
        // box on the left and, in the SAME row, the order date + payment badge +
        // COD/prepaid stacked on the right. This keeps the fixed sections tight so
        // that when an order has several items the item list has room to grow
        // downward instead of the barcode eating a whole row of its own.
        //
        // Barcode value: the allotted courier AWB when present (so the courier team
        // scans straight into their own system at pickup), otherwise our own order
        // code (so the godown/RTO flow can always scan a parcel back to the order).
        main.addCell(barcodeInfoRow(content));

        // 4) Item description + order total. This row is given a minimum height so
        // it absorbs the leftover vertical space of the fixed-height quadrant —
        // the product list therefore has room to grow downward, and any unused
        // space stays here (above the pickup address) rather than as a blank gap
        // at the very bottom of the label.
        main.addCell(itemDescriptionRow(content));

        // 5) Pickup & return address — kept as the VERY LAST section so all the
        // remaining space above it is available for a longer product list.
        main.addCell(captionBox("PICKUP & RETURN ADDRESS", nz(content.pickupReturnAddress(), "\u2014")));

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
        PdfPTable t = new PdfPTable(logo != null ? new float[] {4f, 1f} : new float[] {1f});
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
            // Square-ish mark, aligned to the top-right of the company section.
            logo.scaleToFit(46, 46);
            PdfPCell rc = new PdfPCell(logo, false);
            rc.setBorder(Rectangle.NO_BORDER);
            rc.setHorizontalAlignment(Element.ALIGN_RIGHT);
            rc.setVerticalAlignment(Element.ALIGN_TOP);
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
     * Compact "barcode + info" row (space-saving redesign): a bordered SQUARE
     * barcode box on the left (Code128 of the courier AWB when allotted, else our
     * own order code, with a small caption + human-readable value under it) and,
     * in the SAME row, a right column stacking Ordered-on, the Payment badge and
     * the COD-collect amount (or a "prepaid — do not collect" note). Sitting the
     * barcode beside this info — rather than in a full-width row of its own —
     * saves a whole row of height so multi-item orders have room to expand.
     */
    private PdfPCell barcodeInfoRow(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(new float[] {1.15f, 1f});
        t.setWidthPercentage(100);

        // Left: square barcode box.
        PdfPCell left = new PdfPCell(barcodeSquare(content));
        left.setBorder(Rectangle.RIGHT);
        left.setBorderColor(BORDER);
        left.setPadding(6f);
        left.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(left);

        // Right: order date + payment badge + COD amount, stacked.
        PdfPCell right = new PdfPCell(barcodeSideInfo(content));
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(6f);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(right);

        PdfPCell wrap = new PdfPCell(t);
        wrap.setBorderColor(BORDER);
        wrap.setPadding(0f);
        return wrap;
    }

    /**
     * The square barcode cell: caption (COURIER awb / ORDER) + a barcode scaled to
     * a roughly square footprint + the human-readable value beneath it.
     */
    private PdfPTable barcodeSquare(InternalLabelContent content) {
        boolean courier = content.hasCourierBarcode();
        String value = courier ? content.courierBarcodeValue() : content.orderCode();
        String caption = courier
                ? "COURIER: " + nz(content.courierName(), "\u2014").toUpperCase()
                : "ORDER";
        String human = courier ? "AWB: " + value : content.orderCode();

        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setHorizontalAlignment(Element.ALIGN_CENTER);

        PdfPCell cap = new PdfPCell(new Phrase(caption, CAPTION_FONT));
        cap.setBorder(Rectangle.NO_BORDER);
        cap.setHorizontalAlignment(Element.ALIGN_CENTER);
        cap.setPaddingBottom(2f);
        t.addCell(cap);

        Image barcode = imageOf(barcodeGenerator.code128Png(value));
        // Roughly square footprint so it reads as a "box" rather than a wide strip.
        barcode.scaleToFit(120, 90);
        PdfPCell bc = new PdfPCell(barcode, false);
        bc.setBorder(Rectangle.NO_BORDER);
        bc.setHorizontalAlignment(Element.ALIGN_CENTER);
        bc.setPadding(1f);
        t.addCell(bc);

        PdfPCell hv = new PdfPCell(new Phrase(human, CODE_FONT));
        hv.setBorder(Rectangle.NO_BORDER);
        hv.setHorizontalAlignment(Element.ALIGN_CENTER);
        hv.setPaddingTop(2f);
        t.addCell(hv);
        return t;
    }

    /** The stacked info shown beside the barcode: ordered-on, payment, COD/prepaid. */
    private PdfPTable barcodeSideInfo(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        t.addCell(new Phrase("ORDERED ON", CAPTION_FONT));
        t.addCell(new Phrase(nz(content.orderedOn(), "\u2014"), BODY_FONT));

        t.addCell(spacer());
        t.addCell(new Phrase("PAYMENT", CAPTION_FONT));
        t.addCell(new Phrase(paymentBadge(content), NAME_FONT));

        t.addCell(spacer());
        if (content.codApplicable()) {
            t.addCell(new Phrase("COLLECT ON DELIVERY", CAPTION_FONT));
            t.addCell(new Phrase(money(content.codAmount()), NAME_FONT));
        } else {
            t.addCell(new Phrase("Prepaid \u2014 do not collect", SMALL_FONT));
        }
        return t;
    }

    /** A thin vertical gap phrase used between stacked info groups. */
    private static Phrase spacer() {
        return new Phrase("\n", SMALL_FONT);
    }

    /**
     * Item description (left) + order total (right), given a minimum height so it
     * soaks up the leftover space of the fixed-height quadrant. A short item list
     * simply leaves whitespace HERE (above the pickup address), and a long list
     * grows downward into the same area — either way the blank space is available
     * for the products rather than dangling below the pickup address.
     */
    private PdfPCell itemDescriptionRow(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(new float[] {3.2f, 1f});
        t.setWidthPercentage(100);

        PdfPCell l = new PdfPCell(
                captionValue("ITEM DESCRIPTION", nz(content.itemSummary(), "\u2014"), Element.ALIGN_LEFT));
        l.setBorder(Rectangle.RIGHT);
        l.setBorderColor(BORDER);
        l.setPadding(6f);
        l.setMinimumHeight(ITEM_ROW_MIN_HEIGHT);
        l.setVerticalAlignment(Element.ALIGN_TOP);

        PdfPCell r = new PdfPCell(
                captionValue("TOTAL", money(content.totalAmount()), Element.ALIGN_RIGHT));
        r.setBorder(Rectangle.NO_BORDER);
        r.setPadding(6f);
        r.setVerticalAlignment(Element.ALIGN_TOP);

        t.addCell(l);
        t.addCell(r);

        PdfPCell wrap = new PdfPCell(t);
        wrap.setBorderColor(BORDER);
        wrap.setPadding(0f);
        return wrap;
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

    /**
     * Builds a logo Image from the given bytes, falling back to the bundled
     * {@code brand/shifa_logo_1.png} classpath resource when no Settings logo is
     * supplied (so the label always carries the Shifa mark top-right, even before
     * a logo is uploaded in Settings). Returns {@code null} only when both the
     * supplied bytes and the bundled resource are absent/undecodable.
     */
    private Image logoImage(byte[] logoPng) {
        if (logoPng != null && logoPng.length > 0) {
            try {
                return Image.getInstance(logoPng);
            } catch (Exception ignored) {
                // Fall through to the bundled brand logo below.
            }
        }
        return bundledLogo();
    }

    /** The bundled brand logo, decoded once and cached (or {@code null} if missing). */
    private Image bundledLogo() {
        if (bundledLogoLoaded) {
            return bundledLogo;
        }
        bundledLogoLoaded = true;
        try (java.io.InputStream in = getClass().getResourceAsStream("/brand/shifa_logo_1.png")) {
            if (in != null) {
                bundledLogo = Image.getInstance(in.readAllBytes());
            }
        } catch (Exception e) {
            bundledLogo = null;
        }
        return bundledLogo;
    }
}
