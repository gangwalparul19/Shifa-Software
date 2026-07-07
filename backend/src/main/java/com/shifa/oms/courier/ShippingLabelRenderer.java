package com.shifa.oms.courier;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.label.BarcodeGenerator;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Renders {@link ShippingLabelContent} models to PDF bytes using OpenPDF with a
 * scannable Code128 barcode of the AWB (Req 12.3), reusing the label module's
 * {@link BarcodeGenerator}. Mirrors {@code LabelPdfRenderer}: one label block per
 * page, so a bulk request produces exactly one block per requested order
 * (Req 12.5).
 */
public class ShippingLabelRenderer {

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font COD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);

    private final BarcodeGenerator barcodeGenerator;

    public ShippingLabelRenderer(BarcodeGenerator barcodeGenerator) {
        this.barcodeGenerator = Objects.requireNonNull(barcodeGenerator, "barcodeGenerator");
    }

    /** Renders a single shipping label to a one-page PDF. */
    public byte[] render(ShippingLabelContent content) {
        return render(List.of(content), null);
    }

    /** Renders a single shipping label with an optional company logo. */
    public byte[] render(ShippingLabelContent content, byte[] logoPng) {
        return render(List.of(content), logoPng);
    }

    /**
     * Renders one shipping-label block per content into a single PDF, each on its
     * own page, in the given order (Req 12.5).
     *
     * @param contents the label content blocks (non-empty)
     * @return the generated PDF bytes
     */
    public byte[] render(List<ShippingLabelContent> contents) {
        return render(contents, null);
    }

    /**
     * Renders one shipping-label block per content, embedding the company logo at
     * the top of each block when {@code logoPng} is provided (falls back to the
     * courier-name header when absent, keeping the layout intact).
     *
     * @param contents the label content blocks (non-empty)
     * @param logoPng  the company logo image bytes, or {@code null} for none
     * @return the generated PDF bytes
     */
    public byte[] render(List<ShippingLabelContent> contents, byte[] logoPng) {
        Objects.requireNonNull(contents, "contents");
        if (contents.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NO_LABELS",
                    "At least one shipping label is required to produce a PDF.");
        }
        Document document = new Document(PageSize.A5, 36, 36, 36, 36);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            for (int i = 0; i < contents.size(); i++) {
                if (i > 0) {
                    document.newPage();
                }
                writeLabelBlock(document, contents.get(i), logoPng);
            }
            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SHIPPING_LABEL_PDF_FAILED",
                    "Failed to render the courier shipping label PDF.");
        }
    }

    private void writeLabelBlock(Document document, ShippingLabelContent content, byte[] logoPng)
            throws DocumentException {
        Image logo = logoImage(logoPng);
        if (logo != null) {
            logo.setAlignment(Element.ALIGN_CENTER);
            logo.scaleToFit(150, 60);
            document.add(logo);
        }
        Paragraph title = new Paragraph(content.courierName(), TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        Paragraph awbLine = new Paragraph("AWB: " + content.awb(), HEADING_FONT);
        awbLine.setAlignment(Element.ALIGN_CENTER);
        awbLine.setSpacingAfter(6f);
        document.add(awbLine);

        // Scannable Code128 barcode encoding the AWB (Req 12.3).
        Image barcode = imageOf(barcodeGenerator.code128Png(content.awb()));
        barcode.setAlignment(Element.ALIGN_CENTER);
        barcode.scaleToFit(300, 90);
        document.add(barcode);

        document.add(spacer());
        document.add(new Paragraph("Order: " + content.orderCode(), BODY_FONT));

        document.add(spacer());
        document.add(new Paragraph("Ship To", HEADING_FONT));
        document.add(new Paragraph(content.customerName(), BODY_FONT));
        document.add(new Paragraph(content.fullAddress(), BODY_FONT));
        document.add(new Paragraph("Mobile: " + content.customerMobile(), BODY_FONT));

        document.add(spacer());
        document.add(new Paragraph("Items", HEADING_FONT));
        document.add(itemsTable(content.lineItems()));

        document.add(spacer());
        if (content.codApplicable()) {
            BigDecimal cod = content.codAmount() != null ? content.codAmount() : BigDecimal.ZERO;
            Paragraph codLine = new Paragraph("COLLECT ON DELIVERY: Rs. " + cod.toPlainString(), COD_FONT);
            codLine.setAlignment(Element.ALIGN_CENTER);
            document.add(codLine);
        } else {
            Paragraph paid = new Paragraph("PREPAID - do not collect cash", BODY_FONT);
            paid.setAlignment(Element.ALIGN_CENTER);
            document.add(paid);
        }
    }

    private PdfPTable itemsTable(List<ShippingLabelContent.LabelLineItem> items) {
        PdfPTable table = new PdfPTable(new float[] {4f, 1f});
        table.setWidthPercentage(100);
        table.addCell(headerCell("Product"));
        table.addCell(headerCell("Qty"));
        for (ShippingLabelContent.LabelLineItem item : items) {
            table.addCell(bodyCell(item.productName(), Element.ALIGN_LEFT));
            table.addCell(bodyCell(Integer.toString(item.quantity()), Element.ALIGN_CENTER));
        }
        return table;
    }

    private PdfPCell headerCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, HEADING_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        return cell;
    }

    private PdfPCell bodyCell(String text, int alignment) {
        PdfPCell cell = new PdfPCell(new Phrase(text, BODY_FONT));
        cell.setHorizontalAlignment(alignment);
        return cell;
    }

    private Paragraph spacer() {
        Paragraph p = new Paragraph(" ", BODY_FONT);
        p.setSpacingAfter(2f);
        return p;
    }

    private Image imageOf(byte[] png) {
        try {
            return Image.getInstance(png);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "SHIPPING_LABEL_PDF_FAILED",
                    "Failed to embed the AWB barcode into the shipping label PDF.");
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
