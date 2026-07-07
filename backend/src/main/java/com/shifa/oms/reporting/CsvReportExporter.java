package com.shifa.oms.reporting;

import com.shifa.oms.reporting.domain.TabularData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders a {@link TabularData} to RFC-4180 CSV bytes (Req 23.1). Fields are
 * quoted when they contain a comma, quote, or newline; embedded quotes are
 * doubled. The CSV is a faithful encoding of the table (header row + one line
 * per data row), so parsing it back yields the same table (Property 24).
 *
 * <p>An empty table still emits its header line, satisfying the header-only
 * empty-range export (Req 23.2).
 */
@Component
public class CsvReportExporter {

    /** Renders the table to UTF-8 CSV bytes. */
    public byte[] export(TabularData table) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, table.headers());
        for (List<String> row : table.rows()) {
            appendLine(sb, row);
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendLine(StringBuilder sb, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    private static String escape(String value) {
        String v = value == null ? "" : value;
        boolean needsQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        if (!needsQuote) {
            return v;
        }
        return '"' + v.replace("\"", "\"\"") + '"';
    }
}
