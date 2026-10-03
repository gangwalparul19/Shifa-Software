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
    private static final Color BORDER = new Color(30, 30, 30);
    private static final Color CAPTION_GREY = new Color(110, 110, 110);

    // Fonts sized for an A6 quadrant (four labels to an A4 sheet): compact but
    // still legible after the sheet is cut into four.
    private static final Font NAME_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BRAND_DARK = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8);
    private static final Font SMALL_FONT = FontFactory.getFont(FontFactory.HELVETICA, 7);
    /** Same size/family as {@link #SMALL_FONT} (the address font) but bold — used for the GST No line. */
    private static final Font SMALL_BOLD = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7);
    /** Item-list font (normal lists). */
    private static final Font ITEM_FONT = FontFactory.getFont(FontFactory.HELVETICA, 8);
    /** Smaller item-list font for a long list so more products fit before the quadrant overflows. */
    private static final Font ITEM_FONT_SMALL = FontFactory.getFont(FontFactory.HELVETICA, 6.5f);
    private static final Font CAPTION_FONT = grey(FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6));

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
    // pickup address to the very BOTTOM of the fixed-height quadrant. Sized so
    // item + pickup together fill the quadrant (any excess is clamped by the
    // quadrant's fixed height, so a longer address/list never overflows the page).
    // The "no pickup" variant fills the whole remaining height itself. These are
    // kept slightly UNDER the full leftover so the pickup line is never clipped by
    // the quadrant's fixed height (a small bottom margin is fine; clipping is not).
    private static final float ITEM_ROW_MIN_HEIGHT = 150f;
    private static final float ITEM_ROW_MIN_HEIGHT_NO_PICKUP = 210f;

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
     * Renders one label block as a single bordered box divided into stacked
     * sections: the seller letterhead (brand + address + GST No + logo), the
     * recipient "To :" block (30/70 split), a compact barcode + ordered-on/COD/
     * payment row, an itemized item table with Sub-total + Total, and the
     * pickup-and-return address last — all populated from our data.
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

        // 3) Info + barcode row, split 50/50: the ordered-on / payment / COD info
        // on the left and the barcode/QR box on the right (client layout).
        //
        // Barcode value: the allotted courier AWB when present (so the courier team
        // scans straight into their own system at pickup), otherwise our own order
        // code (so the godown/RTO flow can always scan a parcel back to the order).
        main.addCell(barcodeInfoRow(content));

        // A long order drops the pickup/return address so the extra items have room
        // (the quadrant is a fixed height). Short orders keep it, pinned to the
        // bottom with its content limited to 2 lines.
        boolean showPickup = content.lineItems().size() <= MAX_ITEMS_WITH_PICKUP;

        // 4) Item description + order total. This row absorbs the leftover vertical
        // space of the fixed-height quadrant so the product list has room to grow
        // and the pickup address below is pushed to the bottom. When the pickup
        // address is dropped (long order), this section fills the whole remaining
        // height itself.
        main.addCell(itemDescriptionRow(content, showPickup));

        // 5) Pickup & return address — the VERY LAST section, pinned to the bottom,
        // limited to 2 lines. Dropped entirely for a long order (see above).
        if (showPickup) {
            main.addCell(pickupAddressRow(nz(content.pickupReturnAddress(), "\u2014")));
        }

        return main;
    }

    /** Beyond this many line items the pickup/return address is dropped to make room. */
    private static final int MAX_ITEMS_WITH_PICKUP = 5;

    /** The pickup/return address section, limited to a compact 2-line footprint. */
    private PdfPCell pickupAddressRow(String value) {
        PdfPTable inner = new PdfPTable(1);
        inner.setWidthPercentage(100);
        inner.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        inner.addCell(new Phrase("PICKUP & RETURN ADDRESS", CAPTION_FONT));
        // Collapse any newlines so a multi-line pickup address stays on one wrapped
        // line (2 visual lines at most) rather than several.
        inner.addCell(new Phrase(oneLine(value), SMALL_FONT));
        return boxWrap(inner, 6f);
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
        // GST No directly under the seller address, in the SAME font as the address
        // (SMALL_FONT) but bold (client request) — not a larger code font.
        if (content.sellerGstin() != null && !content.sellerGstin().isBlank()) {
            left.addCell(new Phrase("GST No : " + content.sellerGstin(), SMALL_BOLD));
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

    /**
     * The recipient ("To :") block — full width (100%): the "To :" name (bold,
     * shown ONCE), the mobile number, then the delivery address. The address is
     * cleaned so the customer name is not repeated, and a "City, State - Zip" line
     * is appended ONLY when the address text does not already contain the city
     * (so it is never printed twice).
     */
    private PdfPTable recipientTable(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setPaddingBottom(1f);

        // Name (bold, once) + Mobile, each on its own full-width line.
        String name = nz(content.customerName(), "\u2014").toUpperCase();
        t.addCell(new Phrase("To : " + name, nameFontFor(name)));
        if (content.customerMobile() != null && !content.customerMobile().isBlank()) {
            t.addCell(new Phrase("Mobile : +91 " + content.customerMobile(), SMALL_FONT));
        }

        // Full-width delivery address. The cleaned address lines are JOINED with
        // commas into a single string so a multi-line address wraps to ~2 lines
        // instead of printing one line per entry (saves vertical space).
        t.addCell(new Phrase("DELIVERY ADDRESS", CAPTION_FONT));
        List<String> addressLines = cleanAddressLines(content);
        String addressText = String.join(", ", addressLines);
        t.addCell(new Phrase(addressText.isBlank() ? "\u2014" : addressText, SMALL_FONT));

        // Append "City, State - Zip" ONLY when the address text doesn't already
        // contain the city (avoids duplicating the city/state/zip the salesperson
        // may already have typed into the address).
        String cityStateZip = cityStateZip(content);
        if (cityStateZip != null && !addressTextContainsCity(addressText.toLowerCase(), content)) {
            t.addCell(new Phrase(cityStateZip, SMALL_BOLD));
        }

        return t;
    }

    /** "City, State - Zip" from the structured fields, or {@code null} when all blank. */
    private String cityStateZip(InternalLabelContent content) {
        StringBuilder sb = new StringBuilder();
        if (content.city() != null && !content.city().isBlank()) {
            sb.append(content.city().trim().toUpperCase());
        }
        if (content.state() != null && !content.state().isBlank()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(content.state().trim().toUpperCase());
        }
        if (content.postalCode() != null && !content.postalCode().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" - ");
            }
            sb.append(content.postalCode().trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * Whether the free-text address already contains the city name, so appending
     * the structured "City, State - Zip" line would duplicate it. Keyed on the
     * city (the most reliable signal that the salesperson typed the location into
     * the address). Returns false when no city is on file.
     */
    private static boolean addressTextContainsCity(String addressTextLower, InternalLabelContent content) {
        String city = content.city() == null ? "" : content.city().trim().toLowerCase();
        return !city.isEmpty() && addressTextLower.contains(city);
    }

    /** The name font, shrunk a step for longer names so they don't wrap mid-word. */
    private static Font nameFontFor(String name) {
        int len = name == null ? 0 : name.length();
        if (len > 22) {
            return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
        }
        if (len > 16) {
            return FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9);
        }
        return NAME_FONT;
    }

    /**
     * Cleans the salesperson's free-text address so it is not duplicated with the
     * To-box name and the structured city/state/pin line. Salespeople sometimes
     * type a labelled block into the address field, e.g.
     * <pre>
     *   Name- Haider Ali machhali wala
     *   Address- CAMP-2 GANDHI CHOWK NEAR JANTA SCHOOL
     *   Landmark- SANGAM STUDIO
     *   City- BHILAI
     *   State- CHHATTISGARH
     *   Pin code- 490001
     *   490001, Durg, CHATTISGARH
     * </pre>
     * This drops the lines that merely repeat data shown elsewhere — the customer
     * name (already in the To box), the state and the pincode (already in the
     * structured line) — and strips the {@code "Label- "} prefixes so the address
     * reads cleanly. Lines carrying real location detail (Address/Landmark/City)
     * are kept. Returns the cleaned lines in order (never {@code null}).
     */
    private List<String> cleanAddressLines(InternalLabelContent content) {
        String raw = content.addressLine();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // Normalised (lowercase, whitespace removed) comparison keys so a name like
        // "Haider Ali machhali wala" in the address matches the stored
        // "HAIDER ALI MACHHALIWALA" even when the spacing differs.
        String name = squash(content.customerName());
        String state = squash(content.state());
        String pin = squash(content.postalCode());
        String city = squash(content.city());

        List<String> out = new java.util.ArrayList<>();
        for (String rawLine : raw.split("\\r?\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            // Strip a leading "Label- " / "Label : " field prefix, keep the value.
            String value = stripFieldLabel(line);
            String lower = value.trim().toLowerCase();
            String squashed = squash(value);
            if (lower.isEmpty()) {
                continue;
            }
            // Drop a line that just repeats the customer name (shown in the To box),
            // tolerating spacing differences.
            if (!name.isEmpty() && squashed.equals(name)) {
                continue;
            }
            // Drop a line that is just the state, the city, or the pincode (all
            // shown in the structured city/state/pin line below).
            if (!state.isEmpty() && squashed.equals(state)) {
                continue;
            }
            if (!pin.isEmpty() && squashed.equals(pin)) {
                continue;
            }
            if (!city.isEmpty() && squashed.equals(city)) {
                continue;
            }
            // Drop a line that is a duplicate structured "pin, city, state" tail
            // (contains both the pincode and the state) — the canonical one is
            // appended separately.
            String stateLower = content.state() == null ? "" : content.state().trim().toLowerCase();
            String pinLower = content.postalCode() == null ? "" : content.postalCode().trim().toLowerCase();
            if (!pinLower.isEmpty() && !stateLower.isEmpty()
                    && lower.contains(pinLower) && lower.contains(stateLower)) {
                continue;
            }
            out.add(value.trim());
        }
        return out;
    }

    /** Lowercases and removes ALL whitespace, for spacing-tolerant equality checks. */
    private static String squash(String value) {
        return value == null ? "" : value.trim().toLowerCase().replaceAll("\\s+", "");
    }

    /** Strips a leading {@code "Label-"} / {@code "Label :"} field prefix, keeping the value. */
    private static String stripFieldLabel(String line) {
        // Match a short leading label word(s) followed by '-' or ':' (e.g. "Pin code- 490001").
        java.util.regex.Matcher m = FIELD_LABEL.matcher(line);
        return m.find() ? line.substring(m.end()).trim() : line;
    }

    /** Leading field label like "Name-", "Address :", "Pin code-", "Landmark -". */
    private static final java.util.regex.Pattern FIELD_LABEL =
            java.util.regex.Pattern.compile("^\\s*[A-Za-z][A-Za-z ]{0,14}[-:]\\s*");

    /**
     * The "info + barcode" row, split 50/50: the ordered-on / payment / COD info
     * on the LEFT and the barcode/QR box (Code128 of the courier AWB when
     * allotted, else our own order code) on the RIGHT. Keeping both on one row
     * saves a whole row of height so multi-item orders have room to expand.
     */
    private PdfPCell barcodeInfoRow(InternalLabelContent content) {
        // Info and barcode/QR share the row 50/50 (client layout).
        PdfPTable t = new PdfPTable(new float[] {1f, 1f});
        t.setWidthPercentage(100);

        // Left 50%: Order On / Payment / COD as three inline "label : value" rows.
        PdfPCell left = new PdfPCell(barcodeSideInfo(content));
        left.setBorder(Rectangle.RIGHT);
        left.setBorderColor(BORDER);
        left.setPaddingLeft(4f);
        left.setPaddingRight(6f);
        left.setPaddingTop(4f);
        left.setPaddingBottom(4f);
        left.setVerticalAlignment(Element.ALIGN_MIDDLE);
        t.addCell(left);

        // Right 50%: the barcode/QR box.
        PdfPCell right = new PdfPCell(barcodeSquare(content));
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(3f);
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
        // Fits the 50% column — a larger, easier-to-scan block.
        barcode.scaleToFit(120, 80);
        PdfPCell bc = new PdfPCell(barcode, false);
        bc.setBorder(Rectangle.NO_BORDER);
        bc.setHorizontalAlignment(Element.ALIGN_CENTER);
        bc.setPadding(1f);
        t.addCell(bc);

        PdfPCell hv = new PdfPCell(new Phrase(human, SMALL_BOLD));
        hv.setBorder(Rectangle.NO_BORDER);
        hv.setHorizontalAlignment(Element.ALIGN_CENTER);
        hv.setPaddingTop(2f);
        t.addCell(hv);
        return t;
    }

    /**
     * The info shown in the 70% left part of the barcode row: <b>Ordered on</b>
     * and <b>COD</b> side by side in one two-column row, with <b>Payment</b> on the
     * row below. When a courier AWB has been allotted (the barcode encodes the
     * AWB), our own order code is shown first so it never disappears from the label.
     */
    private PdfPTable barcodeSideInfo(InternalLabelContent content) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        t.getDefaultCell().setPaddingBottom(2f);

        // When a courier AWB has been allotted (the barcode encodes the AWB), show
        // our own order code first so it never disappears from the label.
        if (content.hasCourierBarcode()) {
            t.addCell(inlineLabelValue("Order #", content.orderCode(), BODY_FONT));
        }

        // Three inline rows: "Label: value" each on its own line.
        t.addCell(inlineLabelValue("Order On", nz(content.orderedOn(), "\u2014"), BODY_FONT));
        // Payment value is intentionally a smaller, non-bold font (client request).
        t.addCell(inlineLabelValue("Payment", paymentBadge(content), SMALL_FONT));
        if (content.codApplicable()) {
            t.addCell(inlineLabelValue("COD", money(content.codAmount()), NAME_FONT));
        } else {
            t.addCell(inlineLabelValue("COD", "Prepaid \u2014 nothing to collect", SMALL_FONT));
        }
        return t;
    }

    /**
     * One line reading "<b>Label:</b> value" — the label in the grey caption font
     * and the value in {@code valueFont}, kept together on a single line.
     */
    private Phrase inlineLabelValue(String label, String value, Font valueFont) {
        Phrase p = new Phrase();
        p.add(new com.lowagie.text.Chunk(label + " : ", CAPTION_FONT));
        p.add(new com.lowagie.text.Chunk(nz(value, "\u2014"), valueFont));
        return p;
    }

    /**
     * Itemized item section: a header (ITEM / QTY / AMOUNT), one row per product,
     * then a Sub-total, an optional Discount (when the grand total is below the
     * sub-total), and the Total. Given a minimum height so it soaks up the
     * leftover space of the fixed-height quadrant — a short list leaves whitespace
     * here, a long list grows downward into the same area. The item font shrinks
     * for a long list so more products fit before the quadrant overflows (a very
     * long order is still bounded by the fixed quadrant height, but this fits the
     * common 1-8 item orders comfortably with the Sub-total always shown).
     */
    private PdfPCell itemDescriptionRow(InternalLabelContent content, boolean pickupShownBelow) {
        PdfPTable t = new PdfPTable(new float[] {3f, 0.7f, 1.3f});
        t.setWidthPercentage(100);
        t.getDefaultCell().setBorder(Rectangle.NO_BORDER);

        List<InternalLabelContent.LabelLineItem> items = content.lineItems();
        // Shrink the font for a long list so more rows fit before the quadrant overflows.
        Font itemFont = items.size() > 6 ? ITEM_FONT_SMALL : ITEM_FONT;

        // Header row.
        t.addCell(headerCell("ITEM DESCRIPTION", Element.ALIGN_LEFT));
        t.addCell(headerCell("QTY", Element.ALIGN_CENTER));
        t.addCell(headerCell("AMOUNT", Element.ALIGN_RIGHT));

        boolean anyLineAmount = false;
        for (InternalLabelContent.LabelLineItem li : items) {
            t.addCell(itemCell(li.productName(), Element.ALIGN_LEFT, itemFont));
            t.addCell(itemCell(String.valueOf(li.quantity()), Element.ALIGN_CENTER, itemFont));
            boolean hasAmount = li.lineTotal() != null && li.lineTotal().signum() > 0;
            anyLineAmount = anyLineAmount || hasAmount;
            t.addCell(itemCell(hasAmount ? money(li.lineTotal()) : "", Element.ALIGN_RIGHT, itemFont));
        }
        if (items.isEmpty()) {
            PdfPCell none = itemCell("\u2014", Element.ALIGN_LEFT, itemFont);
            none.setColspan(3);
            t.addCell(none);
        }

        // Totals block: Sub-total (always shown), Discount (if the total is below
        // the sub-total), then the grand Total. Spans the qty+amount columns on the
        // right with the label on the left.
        java.math.BigDecimal subtotal = content.subtotal();
        java.math.BigDecimal total = content.totalAmount() != null
                ? content.totalAmount() : java.math.BigDecimal.ZERO;
        // Fall back to the total as the sub-total when no per-line amounts exist
        // (older orders), so the Sub-total line is never blank/zero misleadingly.
        if (!anyLineAmount || subtotal.signum() <= 0) {
            subtotal = total;
        }
        t.addCell(totalLabelCell("Sub-total"));
        t.addCell(totalValueCell(money(subtotal)));
        java.math.BigDecimal discount = subtotal.subtract(total);
        if (discount.signum() > 0) {
            t.addCell(totalLabelCell("Discount"));
            t.addCell(totalValueCell("- " + money(discount)));
        }
        t.addCell(grandTotalLabelCell("TOTAL"));
        t.addCell(grandTotalValueCell(money(total)));

        PdfPCell wrap = new PdfPCell(t);
        wrap.setBorderColor(BORDER);
        wrap.setPadding(6f);
        // Size this section so it fills the leftover quadrant height — pushing the
        // pickup address (when shown) to the very bottom, or filling the whole
        // remaining height when pickup is dropped (long order). The quadrant's
        // fixed height clamps any excess, so this never spills onto a second page.
        wrap.setMinimumHeight(pickupShownBelow ? ITEM_ROW_MIN_HEIGHT : ITEM_ROW_MIN_HEIGHT_NO_PICKUP);
        wrap.setVerticalAlignment(Element.ALIGN_TOP);
        return wrap;
    }

    // --- Item-table cell helpers --------------------------------------------

    private PdfPCell headerCell(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, CAPTION_FONT));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER);
        c.setHorizontalAlignment(align);
        c.setPaddingBottom(2f);
        return c;
    }

    private PdfPCell itemCell(String text, int align, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        c.setPaddingTop(2f);
        c.setPaddingBottom(1f);
        return c;
    }

    /** Sub-total/Discount label cell — spans the ITEM + QTY columns, right-aligned. */
    private PdfPCell totalLabelCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY_FONT));
        c.setColspan(2);
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(new Color(210, 210, 210));
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setPaddingTop(3f);
        c.setPaddingRight(4f);
        return c;
    }

    private PdfPCell totalValueCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, BODY_FONT));
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(new Color(210, 210, 210));
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setPaddingTop(3f);
        return c;
    }

    private PdfPCell grandTotalLabelCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, NAME_FONT));
        c.setColspan(2);
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(BORDER);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setPaddingTop(3f);
        c.setPaddingRight(4f);
        return c;
    }

    private PdfPCell grandTotalValueCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, NAME_FONT));
        c.setBorder(Rectangle.TOP);
        c.setBorderColor(BORDER);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setPaddingTop(3f);
        return c;
    }

    /** A full-width bordered section wrapping a nested table with the given padding. */
    private PdfPCell boxWrap(PdfPTable inner, float padding) {
        PdfPCell c = new PdfPCell(inner);
        c.setBorderColor(BORDER);
        c.setPadding(padding);
        return c;
    }

    // --- Small helpers ------------------------------------------------------

    /** Collapses any newlines in a value to ", " so it reads as one wrapped line. */
    private static String oneLine(String value) {
        if (value == null) {
            return "\u2014";
        }
        return value.replaceAll("\\s*\\r?\\n\\s*", ", ").trim();
    }

    private String paymentBadge(InternalLabelContent content) {
        String label = nz(content.paymentLabel(), content.codApplicable() ? "COD" : "PREPAID");
        // Present "PREPAID" as the reference's "Pre-Paid" styling.
        return "PREPAID".equalsIgnoreCase(label) ? "Pre-Paid" : label;
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
     * {@code brand/LOGO.png} classpath resource when no Settings logo is
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
        try (java.io.InputStream in = getClass().getResourceAsStream("/brand/LOGO.png")) {
            if (in != null) {
                bundledLogo = Image.getInstance(in.readAllBytes());
            }
        } catch (Exception e) {
            bundledLogo = null;
        }
        return bundledLogo;
    }
}
