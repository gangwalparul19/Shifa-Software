package com.shifa.oms.reporting.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The canonical, format-neutral representation of a displayed report or export:
 * a header row plus zero or more data rows, each a list of already-formatted
 * cell strings.
 *
 * <p>Every exporter (Excel, PDF, Vyapar CSV/Excel) renders from a
 * {@code TabularData}, and the on-screen report is described by the same shape.
 * This is what makes report/export content fidelity checkable at the row-model
 * level (Property 24): the bytes a given exporter emits are a faithful encoding
 * of the {@code TabularData} it was handed, so comparing tables compares content.
 */
public record TabularData(List<String> headers, List<List<String>> rows) {

    public TabularData {
        Objects.requireNonNull(headers, "headers");
        Objects.requireNonNull(rows, "rows");
        headers = List.copyOf(headers);
        List<List<String>> copied = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            copied.add(List.copyOf(row));
        }
        rows = List.copyOf(copied);
    }

    /** A header-only table (no data rows) — used for empty Vyapar exports (Req 23.2). */
    public static TabularData headerOnly(List<String> headers) {
        return new TabularData(headers, List.of());
    }

    /** The number of columns (header cells). */
    public int columnCount() {
        return headers.size();
    }

    /** The number of data rows (excluding the header). */
    public int rowCount() {
        return rows.size();
    }
}
