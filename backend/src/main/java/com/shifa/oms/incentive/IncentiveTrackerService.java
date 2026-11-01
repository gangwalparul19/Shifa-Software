package com.shifa.oms.incentive;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.incentive.IncentiveQueryRepository.LineRow;
import com.shifa.oms.incentive.IncentiveQueryRepository.OrderRow;
import com.shifa.oms.incentive.domain.IncentiveEngine;
import com.shifa.oms.incentive.domain.IncentiveEngine.DayFigure;
import com.shifa.oms.incentive.domain.IncentiveEngine.Person;
import com.shifa.oms.incentive.domain.RuleTimeline;
import com.shifa.oms.incentive.dto.IncentiveProducts;
import com.shifa.oms.incentive.dto.IncentiveRules.RuleVersionView;
import com.shifa.oms.incentive.dto.IncentiveTracker;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Daily sales per salesperson: the month tracker (with the daily incentive) and the day-by-day
 * product report.
 *
 * <p><b>What counts as a sale.</b> The net total of an order (after any discount and rupee
 * round-off), on the Asia/Kolkata day the order was punched, credited to the person who created it
 * (so an admin placing an order on behalf of a salesperson credits that salesperson). Rejected,
 * payment-rejected and cancelled orders are left out, exactly as in the dashboards. Orders nobody
 * created, such as Shopify imports, belong to no salesperson and are not shown.
 *
 * <p>Figures are live: if an order is cancelled or rejected later, the day's sales and bonus drop.
 * Download the report at month end to keep a fixed copy.
 */
@Service
public class IncentiveTrackerService {

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Statuses that never count as a sale (same set the dashboards and P&amp;L use). */
    static final Set<OrderStatus> NOT_A_SALE =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    private static final YearMonth EARLIEST_MONTH = YearMonth.of(2020, 1);
    /** Longest period the product report accepts, in days. */
    static final int MAX_PRODUCT_DAYS = 366;

    private final IncentiveQueryRepository queries;
    private final UserRepository userRepository;
    private final IncentiveRuleService ruleService;
    private final Clock clock;

    @Autowired
    public IncentiveTrackerService(IncentiveQueryRepository queries,
                                   UserRepository userRepository,
                                   IncentiveRuleService ruleService) {
        this(queries, userRepository, ruleService, Clock.system(BUSINESS_ZONE));
    }

    IncentiveTrackerService(IncentiveQueryRepository queries,
                            UserRepository userRepository,
                            IncentiveRuleService ruleService,
                            Clock clock) {
        this.queries = queries;
        this.userRepository = userRepository;
        this.ruleService = ruleService;
        this.clock = clock;
    }

    /** Today in the business time zone. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    // --- Month tracker -----------------------------------------------------

    @Transactional(readOnly = true)
    public IncentiveTracker.Response tracker(YearMonth month) {
        LocalDate today = today();
        requireReportableMonth(month, today);

        Map<Long, User> roster = roster();
        Map<Long, Map<LocalDate, DayFigure>> figures = new HashMap<>();
        for (OrderRow row : queries.orders(month.atDay(1).atStartOfDay(),
                month.plusMonths(1).atDay(1).atStartOfDay(), NOT_A_SALE)) {
            if (row.getCreatedAt() == null || row.getTotal() == null || !roster.containsKey(row.getCreatedBy())) {
                continue;
            }
            figures.computeIfAbsent(row.getCreatedBy(), id -> new HashMap<>())
                    .merge(row.getCreatedAt().toLocalDate(),
                            new DayFigure(1, row.getTotal()),
                            (a, b) -> new DayFigure(a.orders() + b.orders(), a.sales().add(b.sales())));
        }

        // Everyone active, plus anyone inactive who still sold this month, so history is never hidden.
        List<Person> people = new ArrayList<>();
        for (User user : roster.values()) {
            if (user.isActive() || figures.containsKey(user.getId())) {
                people.add(new Person(user.getId(), displayName(user), user.isActive()));
            }
        }

        RuleTimeline timeline = ruleService.timeline();
        IncentiveTracker.Month result = IncentiveEngine.build(month, today, people, figures, timeline);

        Set<Long> versionIds = new HashSet<>();
        for (int d = 1; d <= month.lengthOfMonth(); d++) {
            timeline.inForce(month.atDay(d)).ifPresent(v -> versionIds.add(v.id()));
        }
        List<RuleVersionView> rules = ruleService.viewsOf(versionIds);

        return new IncentiveTracker.Response(month.toString(), month.atDay(1), month.atEndOfMonth(), today,
                result.days(), result.people(), result.dayTotals(), result.totals(), rules, result.rulesDefined());
    }

    // --- Day-by-day product report ----------------------------------------

    @Transactional(readOnly = true)
    public IncentiveProducts.Response products(LocalDate from, LocalDate to, Long personId) {
        LocalDate today = today();
        LocalDate start = from == null ? today.withDayOfMonth(1) : from;
        LocalDate end = to == null ? today : to;
        if (end.isBefore(start)) {
            throw new ValidationException("The start date must not be after the end date.");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_PRODUCT_DAYS) {
            throw new ValidationException("Choose a period of at most " + MAX_PRODUCT_DAYS + " days.");
        }
        Map<Long, User> roster = roster();
        if (personId != null && !roster.containsKey(personId)) {
            throw new ValidationException("That person is not a salesperson.");
        }

        var fromTs = start.atStartOfDay();
        var toTs = end.plusDays(1).atStartOfDay();
        Map<DayPerson, DayAcc> days = new LinkedHashMap<>();
        for (OrderRow row : queries.orders(fromTs, toTs, NOT_A_SALE)) {
            if (row.getCreatedAt() == null || row.getTotal() == null || !wanted(row.getCreatedBy(), roster, personId)) {
                continue;
            }
            DayAcc acc = days.computeIfAbsent(
                    new DayPerson(row.getCreatedAt().toLocalDate(), row.getCreatedBy()), k -> new DayAcc());
            acc.orders++;
            acc.dayTotal = acc.dayTotal.add(row.getTotal());
        }

        Map<ProductKey, ProductAcc> products = new LinkedHashMap<>();
        for (LineRow row : queries.lines(fromTs, toTs, NOT_A_SALE)) {
            if (row.getCreatedAt() == null || !wanted(row.getCreatedBy(), roster, personId)) {
                continue;
            }
            LocalDate date = row.getCreatedAt().toLocalDate();
            String name = row.getProductName() == null || row.getProductName().isBlank()
                    ? "Unnamed item" : row.getProductName().trim();
            ProductAcc acc = products.computeIfAbsent(
                    new ProductKey(date, row.getCreatedBy(), productKey(row.getProductId(), name)),
                    k -> new ProductAcc(name));
            BigDecimal lineValue = row.getLineTotal() == null ? BigDecimal.ZERO : row.getLineTotal();
            acc.quantity += row.getQuantity() == null ? 0 : row.getQuantity();
            acc.sales = acc.sales.add(lineValue);
            acc.orderIds.add(row.getOrderId());
            DayAcc dayAcc = days.computeIfAbsent(new DayPerson(date, row.getCreatedBy()), k -> new DayAcc());
            dayAcc.lineTotal = dayAcc.lineTotal.add(lineValue);
        }

        List<IncentiveProducts.ProductRow> rows = new ArrayList<>();
        Map<String, long[]> productQty = new HashMap<>();
        Map<String, BigDecimal> productSales = new HashMap<>();
        Map<String, String> productNames = new HashMap<>();
        for (Map.Entry<ProductKey, ProductAcc> e : products.entrySet()) {
            ProductKey key = e.getKey();
            ProductAcc acc = e.getValue();
            rows.add(new IncentiveProducts.ProductRow(key.date, key.personId, displayName(roster.get(key.personId)),
                    acc.name, acc.quantity, money(acc.sales), acc.orderIds.size()));
            productQty.computeIfAbsent(key.product, k -> new long[1])[0] += acc.quantity;
            productSales.merge(key.product, acc.sales, BigDecimal::add);
            productNames.putIfAbsent(key.product, acc.name);
        }
        rows.sort(Comparator.comparing(IncentiveProducts.ProductRow::date)
                .thenComparing(IncentiveProducts.ProductRow::personName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.comparing(IncentiveProducts.ProductRow::sales).reversed())
                .thenComparing(IncentiveProducts.ProductRow::productName, String.CASE_INSENSITIVE_ORDER));

        List<IncentiveProducts.DayReconciliation> dayRows = new ArrayList<>();
        long orders = 0;
        BigDecimal dayTotal = BigDecimal.ZERO;
        BigDecimal lineTotal = BigDecimal.ZERO;
        for (Map.Entry<DayPerson, DayAcc> e : days.entrySet()) {
            DayAcc acc = e.getValue();
            dayRows.add(new IncentiveProducts.DayReconciliation(e.getKey().date, e.getKey().personId,
                    displayName(roster.get(e.getKey().personId)), acc.orders, money(acc.dayTotal),
                    money(acc.lineTotal), money(acc.dayTotal.subtract(acc.lineTotal))));
            orders += acc.orders;
            dayTotal = dayTotal.add(acc.dayTotal);
            lineTotal = lineTotal.add(acc.lineTotal);
        }
        dayRows.sort(Comparator.comparing(IncentiveProducts.DayReconciliation::date)
                .thenComparing(IncentiveProducts.DayReconciliation::personName, String.CASE_INSENSITIVE_ORDER));

        long quantity = 0;
        List<IncentiveProducts.ProductTotal> productTotals = new ArrayList<>();
        for (Map.Entry<String, long[]> e : productQty.entrySet()) {
            quantity += e.getValue()[0];
            productTotals.add(new IncentiveProducts.ProductTotal(
                    productNames.get(e.getKey()), e.getValue()[0], money(productSales.get(e.getKey()))));
        }
        productTotals.sort(Comparator.comparing(IncentiveProducts.ProductTotal::sales).reversed()
                .thenComparing(IncentiveProducts.ProductTotal::productName, String.CASE_INSENSITIVE_ORDER));

        return new IncentiveProducts.Response(start, end, personId, rows, dayRows, productTotals,
                new IncentiveProducts.Totals(orders, quantity, money(lineTotal), money(dayTotal)));
    }

    // --- Helpers -----------------------------------------------------------

    /** Salespeople by id. Team leads and admins are not part of the daily incentive. */
    private Map<Long, User> roster() {
        Map<Long, User> roster = new LinkedHashMap<>();
        for (User user : userRepository.findByRoleOrderByCreatedAtDescIdDesc(Role.SALESPERSON)) {
            roster.put(user.getId(), user);
        }
        return roster;
    }

    /**
     * Display name of a user, for the export service. Public on purpose: this bean is proxied for
     * its transactions, and a non-public method called through the proxy would run on the
     * proxy's own (empty) fields.
     */
    public String nameOf(Long personId) {
        return userRepository.findById(personId).map(IncentiveTrackerService::displayName).orElse("Salesperson");
    }

    static String displayName(User user) {
        if (user == null) {
            return "Unknown";
        }
        String full = user.getFullName();
        return full == null || full.isBlank() ? user.getUsername() : full.trim();
    }

    private static boolean wanted(Long creator, Map<Long, User> roster, Long personId) {
        return creator != null && roster.containsKey(creator) && (personId == null || personId.equals(creator));
    }

    /** Group by catalogue product when there is one, otherwise by name ignoring case. */
    private static String productKey(Long productId, String name) {
        return productId != null ? "id:" + productId : "name:" + name.toLowerCase(Locale.ROOT);
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    static void requireReportableMonth(YearMonth month, LocalDate today) {
        if (month.isBefore(EARLIEST_MONTH) || month.isAfter(YearMonth.from(today))) {
            throw new ValidationException("Choose the current month or an earlier one.");
        }
    }

    private record DayPerson(LocalDate date, Long personId) {
    }

    private record ProductKey(LocalDate date, Long personId, String product) {
    }

    private static final class DayAcc {
        long orders;
        BigDecimal dayTotal = BigDecimal.ZERO;
        BigDecimal lineTotal = BigDecimal.ZERO;
    }

    private static final class ProductAcc {
        final String name;
        long quantity;
        BigDecimal sales = BigDecimal.ZERO;
        final Set<Long> orderIds = new TreeSet<>();

        ProductAcc(String name) {
            this.name = name;
        }
    }
}
