package com.shifa.oms.salesperson;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.reporting.ExcelReportExporter;
import com.shifa.oms.reporting.PdfReportExporter;
import com.shifa.oms.reporting.ReportFilenames;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.performance.PerformancePeriod;
import com.shifa.oms.salesperson.dto.LeaderboardResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Complete leaderboard downloads. It delegates ranking and period resolution to
 * {@link MyDayService}, so the screen and the downloaded report use exactly the same
 * salesperson roster, excluded statuses, tie-breaks and IST period boundaries.
 */
@Service
public class LeaderboardExportService {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    /** Finished download bytes and the server-selected filename. */
    public record Export(byte[] body, String filename, MediaType contentType) {
    }

    private final MyDayService myDayService;
    private final ExcelReportExporter excelExporter;
    private final PdfReportExporter pdfExporter;
    private final AuditService auditService;

    public LeaderboardExportService(MyDayService myDayService,
                                    ExcelReportExporter excelExporter,
                                    PdfReportExporter pdfExporter,
                                    AuditService auditService) {
        this.myDayService = myDayService;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
        this.auditService = auditService;
    }

    /**
     * Exports every salesperson in the complete ranking, including zero-sales rows.
     * This method is intentionally not transactional: the ranking read transaction finishes before
     * the export audit is written.
     */
    public Export export(AuthPrincipal principal, PerformancePeriod period,
                         LocalDate customFrom, LocalDate customTo, String format) {
        LeaderboardResponse board = myDayService.leaderboard(principal, period, customFrom, customTo);
        String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        Map<String, TabularData> tables = new LinkedHashMap<>();
        tables.put("Summary", summary(board));
        tables.put("Leaderboard", ranking(board));

        Export export = switch (normalized) {
            case "xlsx", "excel" -> new Export(
                    excelExporter.exportWorkbook(tables),
                    ReportFilenames.of("Leaderboard", board.from(), board.to(), "xlsx"),
                    XLSX);
            case "pdf" -> new Export(
                    pdfExporter.exportWithSections(
                            "Sales Leaderboard",
                            subtitle(board),
                            null,
                            null,
                            tables.entrySet().stream()
                                    .map(e -> new PdfReportExporter.ReportSection(e.getKey(), e.getValue()))
                                    .toList()),
                    ReportFilenames.of("Leaderboard", board.from(), board.to(), "pdf"),
                    MediaType.APPLICATION_PDF);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT",
                    "format must be xlsx or pdf.");
        };

        auditService.record(AuditActions.LEADERBOARD_EXPORTED, AuditActions.ENTITY_ANALYTICS, null,
                "Downloaded the complete sales leaderboard for " + board.periodLabel()
                        + " (" + normalized + ", " + board.rows().size() + " salespeople)");
        return export;
    }

    private static TabularData summary(LeaderboardResponse board) {
        String rank = board.myRank() == null ? "-" : Integer.toString(board.myRank());
        String revenue = board.myRank() == null ? "-" : money(board.myRevenue());
        List<List<String>> rows = List.of(
                List.of("Period", board.periodLabel()),
                List.of("Salespeople included", Integer.toString(board.rows().size())),
                List.of("Your rank", rank),
                List.of("Your period revenue", revenue),
                List.of("Your all-time order streak (days)", Integer.toString(board.myStreakDays())),
                List.of("Revenue definition", "Excludes cancelled, rejected and payment-rejected orders"));
        return new TabularData(List.of("Measure", "Value"), rows);
    }

    private static TabularData ranking(LeaderboardResponse board) {
        List<List<String>> rows = board.rows().stream()
                .map(row -> List.of(
                        Integer.toString(row.rank()),
                        row.name(),
                        Long.toString(row.orders()),
                        money(row.revenue()),
                        row.isMe() ? "Yes" : ""))
                .toList();
        return new TabularData(List.of("Rank", "Salesperson", "Orders", "Revenue (Rs)", "Signed-in user"), rows);
    }

    private static String subtitle(LeaderboardResponse board) {
        return board.periodLabel() + " · Complete ranking of all salespeople, including people with zero sales in this period. "
                + "Revenue and order counts exclude cancelled, rejected and payment-rejected orders. "
                + "The streak shown in the summary is based on the signed-in user's all-time order history.";
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value)
                .setScale(2, java.math.RoundingMode.HALF_UP)
                .toPlainString();
    }
}
