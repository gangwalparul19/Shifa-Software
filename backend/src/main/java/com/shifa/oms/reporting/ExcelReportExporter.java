package com.shifa.oms.reporting;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.reporting.domain.TabularData;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link TabularData} to an {@code .xlsx} workbook using Apache POI
 * (Req 20.4, 23.1). The workbook is a faithful encoding of the table: a bold
 * header row followed by one sheet row per data row, cells in the same column
 * order (Property 24). No values are added or dropped, so reading the workbook
 * back yields the same table.
 */
@Component
public class ExcelReportExporter {

    /** Renders one table into a workbook with a sheet named after the report. */
    public byte[] export(String sheetName, TabularData table) {
        return exportWorkbook(Map.of(sheetName, table));
    }

    /**
     * Renders several related tables into one workbook. Linked map order is
     * preserved so the summary sheet appears before the detail sheets.
     */
    public byte[] exportWorkbook(Map<String, TabularData> tables) {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            Font bold = workbook.createFont();
            bold.setBold(true);
            headerStyle.setFont(bold);

            for (Map.Entry<String, TabularData> entry : tables.entrySet()) {
                TabularData table = entry.getValue();
                Sheet sheet = workbook.createSheet(safeSheetName(entry.getKey()));
                Row header = sheet.createRow(0);
                List<String> headers = table.headers();
                for (int c = 0; c < headers.size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(headers.get(c));
                    cell.setCellStyle(headerStyle);
                }

                int r = 1;
                for (List<String> dataRow : table.rows()) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < dataRow.size(); c++) {
                        row.createCell(c).setCellValue(dataRow.get(c));
                    }
                }
                for (int c = 0; c < headers.size(); c++) {
                    sheet.autoSizeColumn(c);
                }
            }

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REPORT_EXCEL_FAILED",
                    "Failed to render the report Excel file.");
        }
    }

    /** Excel sheet names are limited to 31 chars and may not contain certain symbols. */
    private static String safeSheetName(String name) {
        String base = (name == null || name.isBlank()) ? "Report" : name.replaceAll("[\\\\/*?:\\[\\]]", " ");
        return base.length() > 31 ? base.substring(0, 31) : base;
    }
}
