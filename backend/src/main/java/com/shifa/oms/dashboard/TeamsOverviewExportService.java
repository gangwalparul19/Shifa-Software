package com.shifa.oms.dashboard;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamCallOut;
import com.shifa.oms.dashboard.dto.TeamsOverviewResponse.TeamOverviewRow;
import com.shifa.oms.performance.PerformancePeriod;
import com.shifa.oms.performance.dto.SalespersonPerformanceSummary;
import com.shifa.oms.reporting.ExcelReportExporter;
import com.shifa.oms.reporting.PdfReportExporter;
import com.shifa.oms.reporting.ReportFilenames;
import com.shifa.oms.reporting.domain.TabularData;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Excel and PDF download of the team-wise sales report: one row per team, one row per salesperson
 * and the leads that need a call. The tables are built from the same {@link TeamsOverviewService}
 * result the page shows, so the file always agrees with the screen, and they go through the
 * standard exporters and the {@code shifa-<report>_<dates>.<ext>} file name.
 */
@Service
public class TeamsOverviewExportService {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final String DASH = "-";

    /** A finished download. */
    public record Export(byte[] body, String filename, MediaType contentType) {
    }

    private final TeamsOverviewService overviewService;
    private final ExcelReportExporter excelExporter;
    private final PdfReportExporter pdfExporter;
    private final AuditService auditService;

    public TeamsOverviewExportService(TeamsOverviewService overviewService,
                                      ExcelReportExporter excelExporter,
                                      PdfReportExporter pdfExporter,
                                      AuditService auditService) {
        this.overviewService = overviewService;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
        this.auditService = auditService;
    }

    // Deliberately not transactional: the data is read in the overview service's own read-only
    // transaction, and the audit write afterwards must not run inside a read-only one.

    /**
     * Builds the download.
     *
     * @param team   {@code null}, blank or {@code all} for every team; {@code unassigned} for the
     *               salespeople without a team lead; or a team lead's user id for one team
     * @param format {@code xlsx} or {@code pdf}
     */
    public Export export(PerformancePeriod period, LocalDate from, LocalDate to, String team, String format) {
        String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("xlsx") && !normalized.equals("excel") && !normalized.equals("pdf")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT", "format must be xlsx or pdf.");
        }
        TeamsOverviewResponse data = overviewService.overview(period, from, to);
        List<TeamOverviewRow> rows = select(data, team);
        // Named after the team only when one team was asked for (not when "all" happens to be one).
        boolean oneTeam = !isAll(team);
        String scope = oneTeam ? heading(rows.get(0)) : "All teams";

        Map<String, TabularData> tables = new LinkedHashMap<>();
        tables.put("Teams", teams(rows));
        tables.put("Salespeople", salespeople(rows));
        tables.put("Leads to call", callOuts(rows));

        String title = "Team-wise Sales - " + scope;
        String subtitle = "Period: " + data.periodLabel()
                + (data.from() == null ? "" : " (" + data.from() + " to " + data.to() + ")")
                + ". Orders and sales cover this period; rejected, payment-rejected and cancelled orders are"
                + " not counted as sales. Delivery success, money still to collect, leads and leads to call"
                + " are as of " + data.asOf() + ". At most 25 leads to call are listed per team.";
        String fileTitle = "Team-wise sales" + (oneTeam ? " " + heading(rows.get(0)) : "");

        Export export;
        if (normalized.equals("pdf")) {
            byte[] pdf = pdfExporter.exportWithSections(title, subtitle, null, null,
                    tables.entrySet().stream()
                            .map(e -> new PdfReportExporter.ReportSection(e.getKey(), e.getValue()))
                            .toList());
            export = new Export(pdf, ReportFilenames.of(fileTitle, data.from(), data.to(), "pdf"),
                    MediaType.APPLICATION_PDF);
        } else {
            export = new Export(excelExporter.exportWorkbook(tables),
                    ReportFilenames.of(fileTitle, data.from(), data.to(), "xlsx"), XLSX);
        }
        auditService.record(AuditActions.TEAMS_OVERVIEW_EXPORTED, AuditActions.ENTITY_ANALYTICS, null,
                "Downloaded the team-wise sales report for " + scope + ", " + data.periodLabel()
                        + " (" + normalized + ")");
        return export;
    }

    /** True when the request covers every team ({@code null}, blank or {@code all}). */
    private static boolean isAll(String team) {
        String wanted = team == null ? "" : team.trim().toLowerCase(Locale.ROOT);
        return wanted.isEmpty() || wanted.equals("all");
    }

    /** The teams the download covers, or a 400 when the requested team does not exist. */
    private static List<TeamOverviewRow> select(TeamsOverviewResponse data, String team) {
        List<TeamOverviewRow> all = new ArrayList<>(data.teams());
        if (data.unassigned() != null) {
            all.add(data.unassigned());
        }
        String wanted = team == null ? "" : team.trim().toLowerCase(Locale.ROOT);
        if (isAll(team)) {
            return all;
        }
        if (wanted.equals("unassigned")) {
            if (data.unassigned() == null) {
                throw new ValidationException("There are no unassigned salespeople.");
            }
            return List.of(data.unassigned());
        }
        long id;
        try {
            id = Long.parseLong(wanted);
        } catch (NumberFormatException e) {
            throw new ValidationException("team must be all, unassigned or a team lead id.");
        }
        return all.stream()
                .filter(r -> r.teamLeadId() != null && r.teamLeadId() == id)
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new ValidationException("That team was not found."));
    }

    // --- Tables -------------------------------------------------------------

    private static TabularData teams(List<TeamOverviewRow> rows) {
        List<String> headers = List.of("Team", "Salespeople", "Orders", "Sales (Rs)", "Average order (Rs)",
                "Delivered", "Failed or returned", "Delivery success %", "Still to collect on delivery (Rs)",
                "Leads", "Won", "Lost", "Lead conversion %", "Leads to call");
        List<List<String>> out = new ArrayList<>();
        long orders = 0;
        long delivered = 0;
        long failed = 0;
        long leads = 0;
        long won = 0;
        long lost = 0;
        long toCall = 0;
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal collect = BigDecimal.ZERO;
        for (TeamOverviewRow r : rows) {
            out.add(List.of(heading(r), String.valueOf(r.memberCount()), String.valueOf(r.ordersInPeriod()),
                    money(r.revenueInPeriod()), money(r.averageOrderValue()),
                    String.valueOf(r.delivered()), String.valueOf(r.failed()), percent(r.deliverySuccessRate()),
                    money(r.codOutstanding()), String.valueOf(r.leadsTotal()), String.valueOf(r.leadsWon()),
                    String.valueOf(r.leadsLost()), percent(r.leadConversionRate()), String.valueOf(r.dueFollowUps())));
            orders += r.ordersInPeriod();
            delivered += r.delivered();
            failed += r.failed();
            leads += r.leadsTotal();
            won += r.leadsWon();
            lost += r.leadsLost();
            toCall += r.dueFollowUps();
            sales = sales.add(nz(r.revenueInPeriod()));
            collect = collect.add(nz(r.codOutstanding()));
        }
        if (rows.size() > 1) {
            out.add(List.of("TOTAL", "", String.valueOf(orders), money(sales), "",
                    String.valueOf(delivered), String.valueOf(failed),
                    delivered + failed == 0 ? DASH : percentValue(delivered * 100.0 / (delivered + failed)),
                    money(collect), String.valueOf(leads), String.valueOf(won), String.valueOf(lost),
                    leads == 0 ? DASH : percentValue(won * 100.0 / leads), String.valueOf(toCall)));
        }
        return new TabularData(headers, out);
    }

    private static TabularData salespeople(List<TeamOverviewRow> rows) {
        List<String> headers = List.of("Team", "Salesperson", "Orders", "Sales (Rs)", "Average order (Rs)",
                "Returned (RTO or redispatch)", "Delivered", "Failed or returned", "Delivery success %",
                "Still to collect on delivery (Rs)", "Follow-ups due");
        List<List<String>> out = new ArrayList<>();
        for (TeamOverviewRow r : rows) {
            for (SalespersonPerformanceSummary m : r.members()) {
                out.add(List.of(heading(r), nvl(m.fullName()), String.valueOf(m.ordersInPeriod()),
                        money(m.revenueInPeriod()), money(m.averageOrderValue()), String.valueOf(m.rtoCount()),
                        String.valueOf(m.deliveredCount()), String.valueOf(m.failedCount()),
                        m.deliveredCount() + m.failedCount() == 0 ? DASH : percentValue(m.successRate()),
                        money(m.codOutstanding()), String.valueOf(m.dueFollowUps())));
            }
        }
        return new TabularData(headers, out);
    }

    private static TabularData callOuts(List<TeamOverviewRow> rows) {
        List<String> headers = List.of("Team", "Customer", "Mobile", "Lead source", "Stage", "Follow-up date",
                "Days overdue", "Owner");
        List<List<String>> out = new ArrayList<>();
        for (TeamOverviewRow r : rows) {
            for (TeamCallOut c : r.callOuts()) {
                out.add(List.of(heading(r), nvl(c.customerName()), nvl(c.customerMobile()), nvl(c.leadSource()),
                        nvl(c.status()), c.followUpDate() == null ? DASH : c.followUpDate().toString(),
                        String.valueOf(c.overdueDays()), nvl(c.ownerName())));
            }
        }
        return new TabularData(headers, out);
    }

    // --- Text helpers --------------------------------------------------------

    /** "Team Sameer" from the lead's first name; the unassigned bucket keeps its own name. */
    private static String heading(TeamOverviewRow row) {
        if (row.teamLeadId() == null) {
            return row.teamLeadName();
        }
        String first = row.teamLeadName() == null ? "" : row.teamLeadName().trim().split("\\s+")[0];
        return first.isEmpty() ? String.valueOf(row.teamLeadName()) : "Team " + first;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(Double value) {
        return value == null ? DASH : percentValue(value);
    }

    private static String percentValue(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String nvl(String value) {
        return value == null || value.isBlank() ? DASH : value;
    }
}
