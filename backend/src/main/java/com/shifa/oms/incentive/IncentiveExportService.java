package com.shifa.oms.incentive;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.incentive.dto.IncentiveProducts;
import com.shifa.oms.incentive.dto.IncentiveTracker;
import com.shifa.oms.incentive.dto.IncentiveTracker.DayCell;
import com.shifa.oms.incentive.dto.IncentiveTracker.DayRef;
import com.shifa.oms.incentive.dto.IncentiveTracker.PersonMonth;
import com.shifa.oms.reporting.ExcelReportExporter;
import com.shifa.oms.reporting.PdfReportExporter;
import com.shifa.oms.reporting.ReportFilenames;
import com.shifa.oms.reporting.domain.TabularData;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Excel and PDF downloads of the daily sales reports. Each report is built once as a set of
 * {@link TabularData} tables and handed to the existing exporters, so the file shows exactly what the
 * screen shows and uses the standard {@code shifa-<report>_<dates>.<ext>} file name.
 */
@Service
public class IncentiveExportService {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final DateTimeFormatter MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final String DASH = "-";

    /** A finished download. */
    public record Export(byte[] body, String filename, MediaType contentType) {
    }

    private final IncentiveTrackerService trackerService;
    private final ExcelReportExporter excelExporter;
    private final PdfReportExporter pdfExporter;
    private final AuditService auditService;

    public IncentiveExportService(IncentiveTrackerService trackerService,
                                  ExcelReportExporter excelExporter,
                                  PdfReportExporter pdfExporter,
                                  AuditService auditService) {
        this.trackerService = trackerService;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
        this.auditService = auditService;
    }

    // These methods are deliberately not transactional: the data is read in the tracker service's own
    // read-only transaction, and the audit write afterwards must not run inside a read-only one.

    /** Everyone's sales and bonus for a month. */
    public Export tracker(YearMonth month, String format) {
        IncentiveTracker.Response data = trackerService.tracker(month);
        String title = "Daily Sales and Incentives - " + month.format(MONTH_TITLE);

        Map<String, TabularData> pdf = new LinkedHashMap<>();
        pdf.put("Summary", trackerSummary(data));
        pdf.put("Daily detail", trackerDetail(data));

        Map<String, TabularData> excel = new LinkedHashMap<>(pdf);
        excel.put("Sales grid", grid(data, false));
        excel.put("Bonus grid", grid(data, true));

        Export export = render(format, title, subtitle(data), excel, pdf, ext ->
                ReportFilenames.of("Daily sales and incentives", data.monthStart(), data.monthEnd(), ext));
        audit("Downloaded the daily sales and incentive report for " + month + " (" + format + ")");
        return export;
    }

    /** One person's month, day by day, with the highest and lowest days. */
    public Export calendar(YearMonth month, long personId, String format) {
        IncentiveTracker.Response data = trackerService.tracker(month);
        PersonMonth person = data.people().stream().filter(p -> p.personId() == personId).findFirst()
                .orElseThrow(() -> new ValidationException("There is no sales data for that person in " + month + "."));
        String title = "Sales Calendar - " + person.name() + " - " + month.format(MONTH_TITLE);

        Map<String, TabularData> tables = new LinkedHashMap<>();
        tables.put("Summary", calendarSummary(data, person));
        tables.put("Day by day", calendarDays(data, person));

        Export export = render(format, title, subtitle(data), tables, tables, ext ->
                ReportFilenames.of("Sales calendar " + person.name(), data.monthStart(), data.monthEnd(), ext));
        audit("Downloaded the sales calendar of " + person.name() + " for " + month + " (" + format + ")");
        return export;
    }

    /** Product sales per salesperson per day over a period. */
    public Export products(LocalDate from, LocalDate to, Long personId, String format) {
        IncentiveProducts.Response data = trackerService.products(from, to, personId);
        String who = personId == null ? "" : " " + trackerService.nameOf(personId);
        String title = "Daily Product Sales" + (personId == null ? "" : " - " + trackerService.nameOf(personId));

        Map<String, TabularData> tables = new LinkedHashMap<>();
        tables.put("Products by day", productRows(data));
        tables.put("Day totals", productDays(data));
        tables.put("Product summary", productSummary(data));

        String subtitle = "Period: " + data.from() + " to " + data.to()
                + ". Sales are product line values before any order-level discount.";
        Export export = render(format, title, subtitle, tables, tables, ext ->
                ReportFilenames.of("Daily product sales" + who, data.from(), data.to(), ext));
        audit("Downloaded daily product sales " + data.from() + " to " + data.to()
                + (personId == null ? "" : " for " + trackerService.nameOf(personId)) + " (" + format + ")");
        return export;
    }

    // --- Rendering ---------------------------------------------------------

    private Export render(String format, String title, String subtitle, Map<String, TabularData> excelTables,
                          Map<String, TabularData> pdfTables, Function<String, String> filenameFor) {
        String normalized = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "xlsx", "excel" -> new Export(excelExporter.exportWorkbook(excelTables),
                    filenameFor.apply("xlsx"), XLSX);
            case "pdf" -> new Export(pdfExporter.exportWithSections(title, subtitle, null, null,
                    pdfTables.entrySet().stream()
                            .map(e -> new PdfReportExporter.ReportSection(e.getKey(), e.getValue()))
                            .toList()),
                    filenameFor.apply("pdf"), MediaType.APPLICATION_PDF);
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT",
                    "format must be xlsx or pdf.");
        };
    }

    private void audit(String summary) {
        auditService.record(AuditActions.INCENTIVE_REPORT_EXPORTED, AuditActions.ENTITY_ANALYTICS, null, summary);
    }

    private static String subtitle(IncentiveTracker.Response data) {
        return "Sales are order totals on the day the order was punched (rejected, payment-rejected and "
                + "cancelled orders excluded). The bonus follows the rules in force on each day. "
                + "Highest and lowest days ignore Sundays and days with no sales.";
    }

    // --- Tracker tables ----------------------------------------------------

    private static TabularData trackerSummary(IncentiveTracker.Response data) {
        List<String> headers = List.of("Salesperson", "Orders", "Sales (Rs)", "Bonus days", "Bonus (Rs)",
                "Highest day", "Highest sales (Rs)", "Lowest day", "Lowest sales (Rs)", "No-sale days");
        List<List<String>> rows = new ArrayList<>();
        for (PersonMonth p : data.people()) {
            rows.add(List.of(p.name(), String.valueOf(p.orders()), money(p.sales()),
                    String.valueOf(p.bonusDays()), money(p.bonus()),
                    dayText(p.highest()), sales(p.highest()), dayText(p.lowest()), sales(p.lowest()),
                    String.valueOf(p.noSalesDays())));
        }
        rows.add(List.of("TOTAL", String.valueOf(data.totals().orders()), money(data.totals().sales()),
                String.valueOf(data.totals().bonusDays()), money(data.totals().bonus()),
                "", "", "", "", ""));
        return new TabularData(headers, rows);
    }

    private static TabularData trackerDetail(IncentiveTracker.Response data) {
        List<String> headers = List.of("Date", "Day", "Salesperson", "Orders", "Sales (Rs)", "Bonus (Rs)",
                "Slab reached (Rs)", "Remark");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < data.days().size(); i++) {
            var day = data.days().get(i);
            for (PersonMonth p : sortedByName(data.people())) {
                DayCell cell = p.days().get(i);
                if (cell.orders() == 0) {
                    continue;
                }
                rows.add(List.of(day.date().toString(), weekdayName(day.date()), p.name(),
                        String.valueOf(cell.orders()), money(cell.sales()), money(cell.bonus()),
                        cell.slab() == null ? DASH : money(cell.slab()), remark(day.sunday(), cell)));
            }
        }
        return new TabularData(headers, rows);
    }

    /** Salesperson x day grid of sales (or of bonus) with a total column; Excel only. */
    private static TabularData grid(IncentiveTracker.Response data, boolean bonus) {
        List<String> headers = new ArrayList<>();
        headers.add("Salesperson");
        for (var day : data.days()) {
            headers.add(String.format("%02d %s", day.date().getDayOfMonth(), capitalised(day.weekday())));
        }
        headers.add("Total (Rs)");
        List<List<String>> rows = new ArrayList<>();
        for (PersonMonth p : sortedByName(data.people())) {
            List<String> row = new ArrayList<>();
            row.add(p.name());
            for (DayCell cell : p.days()) {
                BigDecimal value = bonus ? cell.bonus() : cell.sales();
                row.add(value.signum() == 0 ? "" : money(value));
            }
            row.add(money(bonus ? p.bonus() : p.sales()));
            rows.add(row);
        }
        List<String> total = new ArrayList<>();
        total.add("TOTAL");
        for (var day : data.dayTotals()) {
            BigDecimal value = bonus ? day.bonus() : day.sales();
            total.add(value.signum() == 0 ? "" : money(value));
        }
        total.add(money(bonus ? data.totals().bonus() : data.totals().sales()));
        rows.add(total);
        return new TabularData(headers, rows);
    }

    // --- Calendar tables ---------------------------------------------------

    private static TabularData calendarSummary(IncentiveTracker.Response data, PersonMonth p) {
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Salesperson", p.name()));
        rows.add(List.of("Month", data.month()));
        rows.add(List.of("Total sales (Rs)", money(p.sales())));
        rows.add(List.of("Orders", String.valueOf(p.orders())));
        rows.add(List.of("Bonus earned (Rs)", money(p.bonus())));
        rows.add(List.of("Days with a bonus", String.valueOf(p.bonusDays())));
        rows.add(List.of("Selling days", String.valueOf(p.sellingDays())));
        rows.add(List.of("No-sale days (Mon-Sat, up to today)", String.valueOf(p.noSalesDays())));
        rows.add(List.of("Average per selling day (Rs)", money(p.averagePerSellingDay())));
        rows.add(List.of("Highest sales day", describe(p.highest())));
        rows.add(List.of("Lowest sales day", describe(p.lowest())));
        rows.add(List.of("Sunday sales (not used for highest/lowest) (Rs)", money(p.sundaySales())));
        return new TabularData(List.of("Measure", "Value"), rows);
    }

    private static TabularData calendarDays(IncentiveTracker.Response data, PersonMonth p) {
        List<String> headers = List.of("Date", "Day", "Orders", "Sales (Rs)", "Bonus (Rs)", "Slab reached (Rs)", "Remark");
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < data.days().size(); i++) {
            var day = data.days().get(i);
            if (day.future()) {
                continue;
            }
            DayCell cell = p.days().get(i);
            rows.add(List.of(day.date().toString(), weekdayName(day.date()), String.valueOf(cell.orders()),
                    money(cell.sales()), money(cell.bonus()),
                    cell.slab() == null ? DASH : money(cell.slab()), remark(day.sunday(), cell)));
        }
        return new TabularData(headers, rows);
    }

    // --- Product tables ----------------------------------------------------

    private static TabularData productRows(IncentiveProducts.Response data) {
        List<String> headers = List.of("Date", "Day", "Salesperson", "Product", "Quantity", "Sales (Rs)", "Orders");
        List<List<String>> rows = new ArrayList<>();
        for (var r : data.rows()) {
            rows.add(List.of(r.date().toString(), weekdayName(r.date()), r.personName(), r.productName(),
                    String.valueOf(r.quantity()), money(r.sales()), String.valueOf(r.orders())));
        }
        return new TabularData(headers, rows);
    }

    private static TabularData productDays(IncentiveProducts.Response data) {
        List<String> headers = List.of("Date", "Salesperson", "Orders", "Day total (Rs)", "Product lines (Rs)",
                "Discount and round-off (Rs)");
        List<List<String>> rows = new ArrayList<>();
        for (var d : data.days()) {
            rows.add(List.of(d.date().toString(), d.personName(), String.valueOf(d.orders()),
                    money(d.dayTotal()), money(d.lineTotal()), money(d.adjustment())));
        }
        rows.add(List.of("TOTAL", "", String.valueOf(data.totals().orders()), money(data.totals().dayTotal()),
                money(data.totals().lineTotal()), money(data.totals().dayTotal().subtract(data.totals().lineTotal()))));
        return new TabularData(headers, rows);
    }

    private static TabularData productSummary(IncentiveProducts.Response data) {
        List<List<String>> rows = new ArrayList<>();
        for (var p : data.products()) {
            rows.add(List.of(p.productName(), String.valueOf(p.quantity()), money(p.sales())));
        }
        return new TabularData(List.of("Product", "Quantity", "Sales (Rs)"), rows);
    }

    // --- Text helpers ------------------------------------------------------

    private static List<PersonMonth> sortedByName(List<PersonMonth> people) {
        List<PersonMonth> sorted = new ArrayList<>(people);
        sorted.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return sorted;
    }

    private static String remark(boolean sunday, DayCell cell) {
        List<String> parts = new ArrayList<>();
        if (cell.highest()) {
            parts.add("Highest day");
        }
        if (cell.lowest()) {
            parts.add("Lowest day");
        }
        if (sunday) {
            parts.add("Sunday (not used for highest/lowest)");
        }
        if (cell.orders() == 0 && !sunday) {
            parts.add("No sales");
        }
        return String.join("; ", parts);
    }

    /** "2026-11-05", or "2026-11-05 (+1 tied)" when more days share the figure. */
    private static String dayText(List<DayRef> days) {
        if (days.isEmpty()) {
            return DASH;
        }
        return days.get(0).date() + (days.size() > 1 ? " (+" + (days.size() - 1) + " tied)" : "");
    }

    private static String sales(List<DayRef> days) {
        return days.isEmpty() ? DASH : money(days.get(0).sales());
    }

    private static String describe(List<DayRef> days) {
        if (days.isEmpty()) {
            return DASH;
        }
        return dayText(days) + " - Rs " + money(days.get(0).sales());
    }

    private static String money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String weekdayName(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    private static String capitalised(String weekday) {
        return weekday.isEmpty() ? weekday : weekday.charAt(0) + weekday.substring(1).toLowerCase(Locale.ROOT);
    }
}
