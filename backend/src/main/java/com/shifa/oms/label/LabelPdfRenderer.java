package com.shifa.oms.label;

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
import org.springframework.http.HttpStatus;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
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

    private static final Font TITLE_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
    private static final Font HEADING_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11);
    private static final Font BODY_FONT = FontFactory.getFont(FontFactory.HELVETICA, 10);
    private static final Font COD_FONT = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14);

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
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "LABEL_PDF_FAILED",
                    "Failed to render the internal company label PDF.");
        }
    }

    private void writeLabelBlock(Document document, InternalLabelContent content, byte[] logoPng)
            throws DocumentException {
        // Company logo at the top when configured (falls back to the text brand).
        Image logo = logoImage(logoPng);
        if (logo != null) {
            logo.setAlignment(Element.ALIGN_CENTER);
            logo.scaleToFit(150, 60);
            document.add(logo);
        }
        // Company / label heading with the order identifier (Req 10.1).
        Paragraph title = new Paragraph("Shifa Herbal Remedies", TITLE_FONT);
        title.setAlignment(Element.ALIGN_CENTER);
        document.add(title);

        Paragraph orderCode = new Paragraph("Order: " + content.orderCode(), HEADING_FONT);
        orderCode.setAlignment(Element.ALIGN_CENTER);
        orderCode.setSpacingAfter(6f);
        document.add(orderCode);

        // Scannable Code128 barcode encoding the order code (Req 10.1).
        byte[] barcodePng = barcodeGenerator.code128Png(content.barcodeValue());
        Image barcode = imageOf(barcodePng);
        barcode.setAlignment(Element.ALIGN_CENTER);
        barcode.scaleToFit(300, 90);
        document.add(barcode);

        // Customer details (Req 10.1).
        document.add(spacer());
        document.add(new Paragraph("Ship To", HEADING_FONT));
        document.add(new Paragraph(content.customerName(), BODY_FONT));
        document.add(new Paragraph(content.fullAddress(), BODY_FONT));
        document.add(new Paragraph("Mobile: " + content.customerMobile(), BODY_FONT));

        // Line item list: name + quantity (Req 10.1).
        document.add(spacer());
        document.add(new Paragraph("Items", HEADING_FONT));
        document.add(itemsTable(content.lineItems()));

        // COD amount, prominent, only when COD / Partially_Paid (Req 10.2).
        document.add(spacer());
        if (content.codApplicable()) {
            BigDecimal cod = content.codAmount() != null ? content.codAmount() : BigDecimal.ZERO;
            Paragraph codLine = new Paragraph("COLLECT ON DELIVERY: Rs. " + cod.toPlainString(), COD_FONT);
            codLine.setAlignment(Element.ALIGN_CENTER);
            document.add(codLine);
        } else {
            Paragraph paid = new Paragraph("COD: Not applicable (fully paid)", BODY_FONT);
            paid.setAlignment(Element.ALIGN_CENTER);
            document.add(paid);
        }
    }

    private PdfPTable itemsTable(List<InternalLabelContent.LabelLineItem> items) {
        PdfPTable table = new PdfPTable(new float[] {4f, 1f});
        table.setWidthPercentage(100);
        table.addCell(headerCell("Product"));
        table.addCell(headerCell("Qty"));
        for (InternalLabelContent.LabelLineItem item : items) {
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
