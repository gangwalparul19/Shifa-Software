package com.shifa.oms.product;

import java.util.ArrayList;
import java.util.List;

/**
 * A tiny, dependency-free CSV reader for the bulk product import
 * ("operations depth" Feature 2). Handles the common cases the admin's
 * spreadsheet export produces: comma-separated fields, double-quoted fields that
 * may contain commas or newlines, and escaped quotes ({@code ""}).
 *
 * <p>Intentionally minimal — we do not pull in a heavy CSV dependency for a
 * single import endpoint. Rows are split on unquoted line breaks; fields are
 * split on unquoted commas.
 */
final class CsvSupport {

    private CsvSupport() {
    }

    /**
     * Parses CSV text into a list of rows, each a list of field values (already
     * unquoted/unescaped). Blank trailing lines are ignored. A wholly empty input
     * yields an empty list.
     */
    static List<List<String>> parse(String content) {
        List<List<String>> rows = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        boolean rowHasContent = false;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < n && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                    continue;
                }
                field.append(c);
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    inQuotes = true;
                    rowHasContent = true;
                    i++;
                }
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    rowHasContent = true;
                    i++;
                }
                case '\r' -> i++; // ignore; handled by \n
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    if (rowHasContent || current.size() > 1 || !current.get(0).isBlank()) {
                        rows.add(current);
                    }
                    current = new ArrayList<>();
                    rowHasContent = false;
                    i++;
                }
                default -> {
                    field.append(c);
                    rowHasContent = true;
                    i++;
                }
            }
        }
        // Flush the final field/row if the file did not end with a newline.
        current.add(field.toString());
        if (rowHasContent || current.size() > 1 || !current.get(0).isBlank()) {
            rows.add(current);
        }
        return rows;
    }
}
