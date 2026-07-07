package com.shifa.oms.reporting;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.CompanyLogoService;
import com.shifa.oms.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link TabularData} to a landscape PDF using OpenPDF (Req 20.4). The
 * PDF contains a branded Shifa Herbal Remedies header, a title plus a table whose
 * header and body rows mirror the displayed report exactly (Property 24). The row
 * matrix that is written is exposed via {@link #toMatrix(TabularData)} so fidelity
 * can be asserted at the row-model level without parsing PDF bytes.
 *
 * <p>Only the styling is branded here — the exact rows/columns/values are
 * unchanged, preserving Property 24 fidelity.
 */
@Component
public class PdfReportExporter {

    private static final Logger log = LoggerFactory.getLogger(PdfReportExporter.class);

    // --- Shifa Herbal Remedies brand palette (java.awt.Color for OpenPDF) ---
    /** Primary deep herbal green {@code #1F7A4D}. */
    private static final Color BRAND_GREEN = new Color(0x1F, 0x7A, 0x4D);
    /** Dark green for text/emphasis {@code #14532D}. */
    private static final Color DARK_GREEN = new Color(0x14, 0x53, 0x2D);
    /** Gold accent {@code #C8A24A}. */
    private static final Color GOLD = new Color(0xC8, 0xA2, 0x4A);
    /** Light green zebra row tint {@code #EAF3EC}. */
    private static final Color ROW_TINT = new Color(0xEA, 0xF3, 0xEC);
    /** Header text on green: white. */
    private static final Color WHITE = new Color(0xFF, 0xFF, 0xFF);
    /** Body text {@code #243B30}. */
    private static final Color BODY_COLOR = new Color(0x24, 0x3B, 0x30);
    /** Muted footer {@code #6B7B72}. */
    private static final Color MUTED = new Color(0x6B, 0x7B, 0x72);
    /** Thin light-gray table border. */
    private static final Color BORDER_GRAY = new Color(0xD7, 0xE1, 0xDA);

    private static final Font COMPANY_FONT = new Font(Font.HELVETICA, 16, Font.BOLD, WHITE);
    private static final Font TAGLINE_FONT = new Font(Font.HELVETICA, 9, Font.ITALIC, WHITE);
    private static final Font TITLE_FONT = new Font(Font.HELVETICA, 14, Font.BOLD, DARK_GREEN);
    private static final Font HEADER_FONT = new Font(Font.HELVETICA, 9, Font.BOLD, WHITE);
    private static final Font BODY_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, BODY_COLOR);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 7, Font.NORMAL, MUTED);

    private static final String DEFAULT_COMPANY_NAME = "Shifa Herbal Remedies";
    private static final String TAGLINE = "Pure Herbal Wellness, Naturally";
    private static final String DEFAULT_PHONE = "+91 9302590767";
    private static final String CREDIT_LINE =
            "Designed & Developed by Weblithic — https://www.weblithic.com/";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SettingsService settingsService;
    private final CompanyLogoService companyLogoService;

    /**
     * Spring constructor: injects settings + logo services so the branded header
     * can show the configured company name, contact, and logo.
     */
    @Autowired
    public PdfReportExporter(SettingsService settingsService, CompanyLogoService companyLogoService) {
        this.settingsService = settingsService;
        this.companyLogoService = companyLogoService;
    }

    /**
     * No-arg constructor for direct/test instantiation: the branded header falls
     * back to static defaults and no logo, and rendering never fails on missing
     * services.
     */
    public PdfReportExporter() {
        this(null, null);
    }

    /** Renders the table into PDF bytes with the given title. */
    public byte[] export(String title, TabularData table) {
        Document document = new Document(PageSize.A4.rotate(), 24, 24, 24, 24);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();

            writeBrandHeader(document, title);

            List<List<String>> matrix = toMatrix(table);
            int columns = table.columnCount();
            if (columns == 0) {
                document.add(new Paragraph("No columns.", BODY_FONT));
            } else {
                PdfPTable pdfTable = new PdfPTable(columns);
                pdfTable.setWidthPercentage(100);
                // First matrix row is the header, the rest are body rows.
                boolean isHeader = true;
                int bodyIndex = 0;
                for (List<String> row : matrix) {
                    for (String cellValue : row) {
                        pdfTable.addCell(cell(cellValue, isHeader, bodyIndex));
                    }
                    if (!isHeader) {
                        bodyIndex++;
                    }
                    isHeader = false;
                }
                document.add(pdfTable);
            }

            if (table.rowCount() == 0) {
                Paragraph none = new Paragraph("No rows for the selected criteria.", FOOTER_FONT);
                none.setSpacingBefore(8f);
                document.add(none);
            }

            writeFooter(document);

            document.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_PDF_FAILED",
                    "Failed to render the report PDF file.");
        }
    }

    /** Renders the full-width green brand band plus the report title + gold divider. */
    private void writeBrandHeader(Document document, String title) throws DocumentException {
        String companyName = DEFAULT_COMPANY_NAME;
        if (settingsService != null) {
            try {
                AppSettings settings = settingsService.getSettings();
                if (settings != null && settings.getLegalName() != null
                        && !settings.getLegalName().isBlank()) {
                    companyName = settings.getLegalName();
                }
            } catch (RuntimeException e) {
                log.warn("Failed to load settings for report header; using defaults.");
            }
        }

        PdfPTable band = new PdfPTable(new float[] {2f, 8f});
        band.setWidthPercentage(100);

        // Left: logo when present.
        PdfPCell logoCell = new PdfPCell();
        logoCell.setBorder(PdfPCell.NO_BORDER);
        logoCell.setBackgroundColor(BRAND_GREEN);
        logoCell.setPadding(8f);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Image logo = loadLogo();
        if (logo != null) {
            logo.scaleToFit(120, 48);
            logoCell.addElement(logo);
        }
        band.addCell(logoCell);

        // Right: company name + tagline.
        PdfPCell brandCell = new PdfPCell();
        brandCell.setBorder(PdfPCell.NO_BORDER);
        brandCell.setBackgroundColor(BRAND_GREEN);
        brandCell.setPadding(8f);
        brandCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        brandCell.addElement(new Paragraph(companyName, COMPANY_FONT));
        brandCell.addElement(new Paragraph(TAGLINE, TAGLINE_FONT));
        band.addCell(brandCell);

        document.add(band);

        // Thin gold divider rule under the band.
        PdfPTable divider = new PdfPTable(1);
        divider.setWidthPercentage(100);
        PdfPCell rule = new PdfPCell();
        rule.setFixedHeight(3f);
        rule.setBackgroundColor(GOLD);
        rule.setBorder(PdfPCell.NO_BORDER);
        divider.addCell(rule);
        document.add(divider);

        Paragraph heading = new Paragraph(title == null ? "Report" : title, TITLE_FONT);
        heading.setSpacingBefore(8f);
        heading.setSpacingAfter(8f);
        document.add(heading);
    }

    /** Muted footer with company contact, timestamp, and the Weblithic credit line. */
    private void writeFooter(Document document) throws DocumentException {
        String phone = DEFAULT_PHONE;
        String email = null;
        if (settingsService != null) {
            try {
                AppSettings settings = settingsService.getSettings();
                if (settings != null) {
                    if (settings.getContactPhone() != null && !settings.getContactPhone().isBlank()) {
                        phone = settings.getContactPhone();
                    }
                    if (settings.getContactEmail() != null && !settings.getContactEmail().isBlank()) {
                        email = settings.getContactEmail();
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Failed to load settings for report footer; using defaults.");
            }
        }

        StringBuilder contact = new StringBuilder(phone);
        if (email != null) {
            contact.append("  |  ").append(email);
        }
        contact.append("  |  Generated: ").append(LocalDateTime.now().format(TIMESTAMP));

        // Gold divider rule above the footer.
        PdfPTable divider = new PdfPTable(1);
        divider.setWidthPercentage(100);
        divider.setSpacingBefore(10f);
        PdfPCell rule = new PdfPCell();
        rule.setFixedHeight(2f);
        rule.setBackgroundColor(GOLD);
        rule.setBorder(PdfPCell.NO_BORDER);
        divider.addCell(rule);
        document.add(divider);

        Paragraph contactLine = new Paragraph(contact.toString(), FOOTER_FONT);
        contactLine.setSpacingBefore(4f);
        document.add(contactLine);

        Paragraph credit = new Paragraph(CREDIT_LINE, FOOTER_FONT);
        document.add(credit);
    }

    /** Loads the branded logo image, or {@code null} when absent/undecodable. */
    private Image loadLogo() {
        if (companyLogoService == null) {
            return null;
        }
        try {
            byte[] bytes = companyLogoService.currentLogoPng().orElse(null);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            return Image.getInstance(bytes);
        } catch (Exception e) {
            log.warn("Failed to embed company logo into the report PDF; using text brand.");
            return null;
        }
    }

    /**
     * The full cell matrix that {@link #export} renders: row 0 is the header,
     * rows 1..n are the data rows, in column order. Used to verify the PDF
     * carries the same rows/columns as the displayed report (Property 24).
     */
    public static List<List<String>> toMatrix(TabularData table) {
        List<List<String>> matrix = new ArrayList<>();
        matrix.add(new ArrayList<>(table.headers()));
        for (List<String> row : table.rows()) {
            matrix.add(new ArrayList<>(row));
        }
        return matrix;
    }

    private static PdfPCell cell(String value, boolean header, int bodyIndex) {
        PdfPCell cell = new PdfPCell(new Phrase(value == null ? "" : value, header ? HEADER_FONT : BODY_FONT));
        cell.setHorizontalAlignment(Element.ALIGN_LEFT);
        cell.setPadding(header ? 5f : 3f);
        if (header) {
            cell.setBackgroundColor(BRAND_GREEN);
            cell.setBorderColor(BRAND_GREEN);
        } else {
            // Zebra striping: alternate light-green tint and white.
            cell.setBackgroundColor(bodyIndex % 2 == 0 ? WHITE : ROW_TINT);
            cell.setBorderColor(BORDER_GRAY);
            cell.setBorderWidth(0.5f);
        }
        return cell;
    }
}
