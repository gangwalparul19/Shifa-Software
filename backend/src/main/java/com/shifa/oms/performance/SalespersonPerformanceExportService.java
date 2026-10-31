package com.shifa.oms.performance;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.lead.LeadEntity;
import com.shifa.oms.lead.LeadRepository;
import com.shifa.oms.lead.LeadStatus;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.reporting.ExcelReportExporter;
import com.shifa.oms.reporting.PdfReportExporter;
import com.shifa.oms.reporting.domain.TabularData;
import com.shifa.oms.reporting.dto.ReportSummary;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds complete performance-owner 360 workbooks and PDFs from one server-side period
 * definition. The exports deliberately do not reuse the drawer's ten-order
 * preview or its 60-day browser trend, so Overall and every selected period
 * contain the complete matching detail.
 */
@Service
public class SalespersonPerformanceExportService {

    private static final List<Role> PERFORMANCE_ROLES =
            List.of(Role.ADMIN, Role.TEAM_LEAD, Role.SALESPERSON);
    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);
    private static final Set<OrderStatus> DELIVERED = EnumSet.of(
            OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);
    private static final Set<OrderStatus> FAILED = EnumSet.of(
            OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
            OrderStatus.RTO, OrderStatus.REDISPATCH);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm");

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final LeadRepository leadRepository;
    private final ExcelReportExporter excelExporter;
    private final PdfReportExporter pdfExporter;
    private final Clock clock = Clock.system(PerformancePeriod.BUSINESS_ZONE);

    public SalespersonPerformanceExportService(OrderRepository orderRepository,
                                               UserRepository userRepository,
                                               LeadRepository leadRepository,
                                               ExcelReportExporter excelExporter,
                                               PdfReportExporter pdfExporter) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.leadRepository = leadRepository;
        this.excelExporter = excelExporter;
        this.pdfExporter = pdfExporter;
    }

    /** Exports the full team comparison shown by the Salespeople directory. */
    @Transactional(readOnly = true)
    public byte[] exportTeam(PerformancePeriod period, String format) {
        LocalDate today = LocalDate.now(clock);
        PerformancePeriod.Window window = period.window(today);
        List<PersonReport> people = userRepository.findByRoleInOrderByCreatedAtDescIdDesc(PERFORMANCE_ROLES)
                .stream()
                .map(user -> buildPerson(user, period, window, today))
                .sorted(Comparator.comparing(PersonReport::sales, Comparator.reverseOrder())
                        .thenComparing(PersonReport::name))
                .toList();
        TeamReport report = buildTeamReport(people, period, window);
        return renderTeam(report, format);
    }

    /**
     * The display name used to name a person's downloaded file (full name, else
     * username). A missing user yields a neutral label rather than failing, since
     * {@link #exportPerson} has already rejected unknown ids by the time this runs.
     */
    @Transactional(readOnly = true)
    public String ownerName(Long id) {
        return userRepository.findById(id)
                .map(user -> user.getFullName() == null || user.getFullName().isBlank()
                        ? user.getUsername() : user.getFullName())
                .orElse("user " + id);
    }

    /** Exports every selected-period detail for one performance owner. */
    @Transactional(readOnly = true)
    public byte[] exportPerson(Long id, PerformancePeriod period, String format) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " does not exist."));
        if (!PERFORMANCE_ROLES.contains(user.getRole())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOT_PERFORMANCE_OWNER",
                    "This account is not an allowed performance owner.");
        }
        LocalDate today = LocalDate.now(clock);
        PerformancePeriod.Window window = period.window(today);
        PersonReport report = buildPerson(user, period, window, today);
        return renderPerson(report, format);
    }

    private PersonReport buildPerson(User user, PerformancePeriod period,
                                     PerformancePeriod.Window window, LocalDate today) {
        List<OrderEntity> orders = orderRepository.findAllScoped(user.getId()).stream()
                .filter(order -> matches(order.getCreatedAt(), window))
                .toList();
        List<LeadEntity> leads = leadRepository.findAllScoped(user.getId()).stream()
                .filter(lead -> matches(lead.getCreatedAt(), window))
                .toList();
        Metrics metrics = metrics(orders, leads, today);
        List<Daily> daily = daily(orders, window, period);
        return new PersonReport(user, orders, leads, metrics, daily, period, window.label());
    }

    private TeamReport buildTeamReport(List<PersonReport> people,
                                       PerformancePeriod period,
                                       PerformancePeriod.Window window) {
        List<OrderLine> orders = new ArrayList<>();
        List<LeadLine> leads = new ArrayList<>();
        Map<LocalDate, DailyAccumulator> byDate = new LinkedHashMap<>();
        for (PersonReport person : people) {
            for (OrderEntity order : person.orders()) {
                orders.add(new OrderLine(person.name(), roleLabel(person.user().getRole()), order));
                addDaily(byDate, order);
            }
            for (LeadEntity lead : person.leads()) {
                leads.add(new LeadLine(person.name(), roleLabel(person.user().getRole()), lead));
            }
        }
        List<Daily> daily = completeDaily(byDate, window, period);
        Metrics totals = metrics(orders.stream().map(OrderLine::order).toList(),
                leads.stream().map(LeadLine::lead).toList(), LocalDate.now(clock));
        return new TeamReport(people, orders, leads, totals, daily, period, window.label());
    }

    private byte[] renderTeam(TeamReport report, String format) {
        TabularData summary = teamSummaryTable(report.people());
        TabularData daily = dailyTable(report.daily());
        TabularData orders = teamOrdersTable(report.orders());
        TabularData leads = teamLeadsTable(report.leads());
        String title = "Admin & Sales Performance — Team";
        String subtitle = report.period().label() + " · " + report.dailyLabel();
        return render(format, title, subtitle, report.summary(), daily, orderedTables(
                        "Summary", summary, "Daily Activity", daily, "Orders", orders, "Leads", leads));
    }

    private byte[] renderPerson(PersonReport report, String format) {
        TabularData summary = personSummaryTable(report);
        TabularData daily = dailyTable(report.daily());
        TabularData orders = personOrdersTable(report.orders());
        TabularData leads = personLeadsTable(report.leads());
        String title = roleLabel(report.user().getRole()) + " Performance — " + report.name();
        String subtitle = report.period().label() + " · " + report.dailyLabel();
        return render(format, title, subtitle, report.metrics(), daily, orderedTables(
                        "Summary", summary, "Daily Activity", daily, "Orders", orders, "Leads", leads));
    }

    private byte[] render(String format, String title, String subtitle,
                          Metrics metrics, TabularData chart, Map<String, TabularData> tables) {
        return switch (format == null ? "" : format.trim().toLowerCase()) {
            case "xlsx", "excel" -> excelExporter.exportWorkbook(tables);
            case "pdf" -> pdfExporter.exportWithSections(title, subtitle, reportSummary(metrics), chart,
                    tables.entrySet().stream()
                            .map(entry -> new PdfReportExporter.ReportSection(entry.getKey(), entry.getValue()))
                            .toList());
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FORMAT",
                    "Unsupported export format: " + format + " (expected xlsx or pdf).");
        };
    }

    private static LinkedHashMap<String, TabularData> orderedTables(Object... values) {
        LinkedHashMap<String, TabularData> tables = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            tables.put((String) values[i], (TabularData) values[i + 1]);
        }
        return tables;
    }

    private static ReportSummary reportSummary(Metrics metrics) {
        return new ReportSummary(metrics.sales(), metrics.orders(), false, null,
                null, null, null, null, BigDecimal.ZERO, metrics.outstanding(), BigDecimal.ZERO);
    }

    private static TabularData teamSummaryTable(List<PersonReport> people) {
        List<List<String>> rows = new ArrayList<>();
        for (PersonReport person : people) {
            rows.add(List.of(person.name(), roleLabel(person.user().getRole()),
                    Long.toString(person.metrics().orders()), money(person.sales()),
                    Long.toString(person.metrics().delivered()), Long.toString(person.metrics().failed()),
                    percent(person.metrics().successRate()), money(person.metrics().outstanding()),
                    Long.toString(person.metrics().leads())));
        }
        return new TabularData(List.of("Owner", "Role", "Orders", "Sales",
                "Delivered", "Failed / RTO", "Delivery Success %", "Outstanding", "Leads"), rows);
    }

    private static TabularData personSummaryTable(PersonReport person) {
        Metrics m = person.metrics();
        List<List<String>> rows = List.of(
                List.of("Owner", person.name()),
                List.of("Username", person.user().getUsername()),
                List.of("Role", roleLabel(person.user().getRole())),
                List.of("Period", person.period().label()),
                List.of("Orders", Long.toString(m.orders())),
                List.of("Sales", money(m.sales())),
                List.of("Average order value", money(m.averageOrderValue())),
                List.of("Delivered", Long.toString(m.delivered())),
                List.of("Failed / RTO", Long.toString(m.failed())),
                List.of("Delivery success", percent(m.successRate())),
                List.of("Outstanding on delivery", money(m.outstanding())),
                List.of("Leads", Long.toString(m.leads())),
                List.of("Converted leads", Long.toString(m.wonLeads())),
                List.of("Lost leads", Long.toString(m.lostLeads())),
                List.of("Active leads", Long.toString(m.activeLeads())),
                List.of("Lead conversion", percent(m.leadConversionRate())),
                List.of("Due follow-ups", Long.toString(m.dueFollowUps())));
        return new TabularData(List.of("Metric", "Value"), rows);
    }

    private static TabularData dailyTable(List<Daily> daily) {
        List<List<String>> rows = daily.stream()
                .map(d -> List.of(d.date().toString(), Long.toString(d.orders()), money(d.sales())))
                .toList();
        return new TabularData(List.of("Date", "Orders", "Sales"), rows);
    }

    private static TabularData personOrdersTable(List<OrderEntity> orders) {
        return new TabularData(List.of("Order Code", "Customer", "Mobile", "Total", "Status",
                "Payment", "Created At"), orders.stream().map(o -> List.of(
                        text(o.getOrderCode()), text(o.getCustomerName()), text(o.getCustomerMobile()),
                        money(o.getTotalAmount()), name(o.getOrderStatus()), name(o.getPaymentStatus()),
                        dateTime(o.getCreatedAt()))).toList());
    }

    private static TabularData teamOrdersTable(List<OrderLine> orders) {
        return new TabularData(List.of("Owner", "Owner Role", "Order Code", "Customer", "Mobile", "Total",
                "Status", "Payment", "Created At"), orders.stream().map(line -> List.of(
                        line.ownerName(), line.ownerRole(), text(line.order().getOrderCode()), text(line.order().getCustomerName()),
                        text(line.order().getCustomerMobile()), money(line.order().getTotalAmount()),
                        name(line.order().getOrderStatus()), name(line.order().getPaymentStatus()),
                        dateTime(line.order().getCreatedAt()))).toList());
    }

    private static TabularData personLeadsTable(List<LeadEntity> leads) {
        return new TabularData(List.of("Customer", "Mobile", "Source", "Status", "Lost Reason",
                "Follow-up Date", "Created At"), leads.stream().map(l -> List.of(
                        text(l.getCustomerName()), text(l.getCustomerMobile()), name(l.getLeadSource()),
                        name(l.getStatus()), name(l.getLostReason()),
                        l.getFollowUpDate() == null ? "" : l.getFollowUpDate().toString(),
                        dateTime(l.getCreatedAt()))).toList());
    }

    private static TabularData teamLeadsTable(List<LeadLine> leads) {
        return new TabularData(List.of("Owner", "Owner Role", "Customer", "Mobile", "Source", "Status",
                "Lost Reason", "Follow-up Date", "Created At"), leads.stream().map(line -> List.of(
                        line.ownerName(), line.ownerRole(), text(line.lead().getCustomerName()), text(line.lead().getCustomerMobile()),
                        name(line.lead().getLeadSource()), name(line.lead().getStatus()), name(line.lead().getLostReason()),
                        line.lead().getFollowUpDate() == null ? "" : line.lead().getFollowUpDate().toString(),
                        dateTime(line.lead().getCreatedAt()))).toList());
    }

    private static Metrics metrics(List<OrderEntity> orders, List<LeadEntity> leads, LocalDate today) {
        BigDecimal sales = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        long delivered = 0;
        long failed = 0;
        for (OrderEntity order : orders) {
            if (!NON_REVENUE.contains(order.getOrderStatus())) {
                sales = sales.add(nz(order.getTotalAmount()));
            }
            outstanding = outstanding.add(nz(order.getCustomerOutstanding()));
            if (DELIVERED.contains(order.getOrderStatus())) {
                delivered++;
            } else if (FAILED.contains(order.getOrderStatus())) {
                failed++;
            }
        }
        long won = leads.stream().filter(l -> l.getStatus() == LeadStatus.WON).count();
        long lost = leads.stream().filter(l -> l.getStatus() == LeadStatus.LOST).count();
        long active = leads.size() - won - lost;
        long due = leads.stream().filter(l -> l.getFollowUpDate() != null
                && !l.getFollowUpDate().isAfter(today)
                && l.getStatus() != LeadStatus.WON && l.getStatus() != LeadStatus.LOST).count();
        double success = delivered + failed == 0 ? 0.0
                : BigDecimal.valueOf(delivered * 100.0 / (delivered + failed))
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
        double conversion = leads.isEmpty() ? 0.0
                : BigDecimal.valueOf(won * 100.0 / leads.size())
                .setScale(1, RoundingMode.HALF_UP).doubleValue();
        BigDecimal average = orders.isEmpty() ? BigDecimal.ZERO
                : sales.divide(BigDecimal.valueOf(orders.size()), 2, RoundingMode.HALF_UP);
        return new Metrics(orders.size(), sales, average, delivered, failed, success, outstanding,
                leads.size(), won, lost, active, conversion, due);
    }

    private static List<Daily> daily(List<OrderEntity> orders, PerformancePeriod.Window window,
                                     PerformancePeriod period) {
        Map<LocalDate, DailyAccumulator> byDate = new LinkedHashMap<>();
        for (OrderEntity order : orders) {
            addDaily(byDate, order);
        }
        return completeDaily(byDate, window, period);
    }

    private static void addDaily(Map<LocalDate, DailyAccumulator> byDate, OrderEntity order) {
        if (order.getCreatedAt() == null) {
            return;
        }
        LocalDate date = order.getCreatedAt().toLocalDate();
        DailyAccumulator accumulator = byDate.computeIfAbsent(date, ignored -> new DailyAccumulator());
        accumulator.orders++;
        if (!NON_REVENUE.contains(order.getOrderStatus())) {
            accumulator.sales = accumulator.sales.add(nz(order.getTotalAmount()));
        }
    }

    private static List<Daily> completeDaily(Map<LocalDate, DailyAccumulator> byDate,
                                             PerformancePeriod.Window window,
                                             PerformancePeriod period) {
        if (period == PerformancePeriod.OVERALL) {
            return byDate.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(entry -> new Daily(entry.getKey(), entry.getValue().orders, entry.getValue().sales))
                    .toList();
        }
        List<Daily> result = new ArrayList<>();
        LocalDate date = window.from().toLocalDate();
        LocalDate last = window.to().toLocalDate().minusDays(1);
        while (!date.isAfter(last)) {
            DailyAccumulator value = byDate.getOrDefault(date, new DailyAccumulator());
            result.add(new Daily(date, value.orders, value.sales));
            date = date.plusDays(1);
        }
        return result;
    }

    private static boolean matches(LocalDateTime timestamp, PerformancePeriod.Window window) {
        return window.from() == null || (timestamp != null
                && !timestamp.isBefore(window.from()) && timestamp.isBefore(window.to()));
    }

    private static String money(BigDecimal value) {
        return nz(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String dateTime(LocalDateTime value) {
        return value == null ? "" : value.format(DATE_TIME);
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String name(Enum<?> value) {
        return value == null ? "" : value.name();
    }

    private static String roleLabel(Role role) {
        return role == Role.ADMIN ? "Admin"
                : role == Role.TEAM_LEAD ? "Team Lead" : "Salesperson";
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record PersonReport(User user, List<OrderEntity> orders, List<LeadEntity> leads,
                               Metrics metrics, List<Daily> daily, PerformancePeriod period,
                               String dailyLabel) {
        String name() {
            return user.getFullName() == null || user.getFullName().isBlank()
                    ? user.getUsername() : user.getFullName();
        }

        BigDecimal sales() {
            return metrics.sales();
        }
    }

    private record TeamReport(List<PersonReport> people, List<OrderLine> orders,
                              List<LeadLine> leads, Metrics summary, List<Daily> daily,
                              PerformancePeriod period, String dailyLabel) {
    }

    private record OrderLine(String ownerName, String ownerRole, OrderEntity order) {
    }

    private record LeadLine(String ownerName, String ownerRole, LeadEntity lead) {
    }

    private record Daily(LocalDate date, long orders, BigDecimal sales) {
    }

    private static final class DailyAccumulator {
        private long orders;
        private BigDecimal sales = BigDecimal.ZERO;
    }

    private record Metrics(long orders, BigDecimal sales, BigDecimal averageOrderValue,
                           long delivered, long failed, double successRate,
                           BigDecimal outstanding, long leads, long wonLeads,
                           long lostLeads, long activeLeads, double leadConversionRate,
                           long dueFollowUps) {
    }
}
