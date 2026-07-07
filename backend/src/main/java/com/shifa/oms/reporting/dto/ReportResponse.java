package com.shifa.oms.reporting.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * A generated report as returned to the Admin UI (Req 20.1, 20.2, 20.3). Carries
 * the report type, the applied window bounds, the displayed table (headers +
 * rows, exactly what the export files reproduce), and the headline metrics.
 */
public record ReportResponse(
        String type,
        LocalDate from,
        LocalDate to,
        List<String> headers,
        List<List<String>> rows,
        ReportSummary summary) {
}
