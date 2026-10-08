package com.shifa.oms.common;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a courier-supplied remittance sheet — either a real Excel workbook
 * ({@code .xlsx}/{@code .xls}, via Apache POI, already a dependency for report
 * exports) or a plain CSV (via {@link CsvParser}) — into the same
 * {@code List<List<String>>} row shape {@link RemittanceImportService} already
 * consumes, so one matching/settlement code path serves both formats.
 *
 * <p>Every cell is rendered to a plain string: text as-is, numbers without
 * scientific notation or a trailing {@code .0} (a "COD Amount" column read as a
 * numeric Excel cell must come out {@code "760"}, not {@code "760.0"}), and an
 * Excel date cell as {@code yyyy-MM-dd} so the existing date-parsing in the
 * importer keeps working unchanged regardless of source format.
 */
public final class SpreadsheetParser {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private SpreadsheetParser() {
    }

    /** Whether the given filename looks like an Excel workbook rather than a CSV. */
    public static boolean isExcel(String filename) {
        if (filename == null) {
            return false;
        }
        String lower = filename.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    /**
     * Parses the given file bytes into rows of string cells, dispatching to the
     * Excel or CSV reader based on {@code filename}'s extension (defaults to CSV
     * when the filename is missing/unrecognised).
     */
    public static List<List<String>> parse(byte[] bytes, String filename) throws IOException {
        if (isExcel(filename)) {
            return parseExcel(bytes);
        }
        String content = new String(bytes == null ? new byte[0] : bytes, StandardCharsets.UTF_8);
        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        return CsvParser.parse(content);
    }

    /** Parses the first sheet of an Excel workbook into rows of string cells. */
    public static List<List<String>> parseExcel(byte[] bytes) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                return rows;
            }
            Sheet sheet = workbook.getSheetAt(0);
            int lastRow = sheet.getLastRowNum();
            for (int r = 0; r <= lastRow; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                int lastCell = row.getLastCellNum();
                if (lastCell < 0) {
                    continue;
                }
                List<String> cells = new ArrayList<>(lastCell);
                boolean anyContent = false;
                for (int c = 0; c < lastCell; c++) {
                    String value = cellText(row.getCell(c));
                    if (!value.isBlank()) {
                        anyContent = true;
                    }
                    cells.add(value);
                }
                if (anyContent) {
                    rows.add(cells);
                }
            }
        }
        return rows;
    }

    private static String cellText(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case BLANK -> "";
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            case NUMERIC -> numericCellText(cell);
            default -> "";
        };
    }

    /** Renders a numeric cell as a plain decimal string, or an ISO date when date-formatted. */
    private static String numericCellText(Cell cell) {
        if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
            LocalDate date = cell.getLocalDateTimeCellValue().toLocalDate();
            return date.format(ISO_DATE);
        }
        double value = cell.getNumericCellValue();
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            // Whole number (typical for an id/amount column) — no trailing ".0".
            return BigDecimal.valueOf((long) value).toPlainString();
        }
        DecimalFormat format = new DecimalFormat("0.##");
        return format.format(value);
    }
}
