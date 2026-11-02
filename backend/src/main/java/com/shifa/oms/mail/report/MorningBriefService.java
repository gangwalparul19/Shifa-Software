package com.shifa.oms.mail.report;

import com.shifa.oms.dashboard.DashboardChannel;
import com.shifa.oms.dashboard.OwnerSnapshotService;
import com.shifa.oms.dashboard.dto.OwnerSnapshotResponse;
import com.shifa.oms.incentive.IncentiveRuleService;
import com.shifa.oms.incentive.IncentiveTrackerService;
import com.shifa.oms.incentive.domain.RuleTimeline;
import com.shifa.oms.incentive.dto.IncentiveTracker;
import com.shifa.oms.mail.report.MorningBrief.Attention;
import com.shifa.oms.mail.report.MorningBrief.Banks;
import com.shifa.oms.mail.report.MorningBrief.BankRow;
import com.shifa.oms.mail.report.MorningBrief.CashOnDelivery;
import com.shifa.oms.mail.report.MorningBrief.ChannelRow;
import com.shifa.oms.mail.report.MorningBrief.Comparison;
import com.shifa.oms.mail.report.MorningBrief.Dues;
import com.shifa.oms.mail.report.MorningBrief.Earner;
import com.shifa.oms.mail.report.MorningBrief.Headline;
import com.shifa.oms.mail.report.MorningBrief.Highlights;
import com.shifa.oms.mail.report.MorningBrief.Incentive;
import com.shifa.oms.mail.report.MorningBrief.PersonRow;
import com.shifa.oms.mail.report.MorningBrief.ProductRow;
import com.shifa.oms.mail.report.MorningBrief.Slab;
import com.shifa.oms.mail.report.MorningBriefQueryRepository.CodReceivedRow;
import com.shifa.oms.mail.report.MorningBriefQueryRepository.OutcomeRow;
import com.shifa.oms.mail.report.MorningBriefQueryRepository.ReceiptRow;
import com.shifa.oms.mail.report.MorningBriefQueryRepository.SalesRow;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.reconciliation.CodAgingService;
import com.shifa.oms.reconciliation.ReconciliationService;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.reconciliation.dto.CollectibleSummaryResponse;
import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Collects the figures for the morning email ({@link MorningBrief}) for one business day.
 *
 * <p><b>Definitions</b> (the same ones the dashboards use, so the email agrees with the app):
 * <ul>
 *   <li><b>Sales</b> are order totals on the day the order was placed (Asia/Kolkata), with rejected,
 *       payment-rejected and cancelled orders left out. Those are counted separately as lost orders.</li>
 *   <li><b>Sources</b> follow {@link DashboardChannel}: Portal is everything that is neither Shopify
 *       nor a store sale.</li>
 *   <li><b>Money in banks</b> is what the Settlement Center calls "OMS booked": payments by the time
 *       they were captured, grouped by receiving account.</li>
 *   <li><b>Cash on delivery to collect</b> is the amount still due on delivery on the day's orders.</li>
 *   <li><b>Top salespeople and the incentive</b> come from the daily sales tracker, so the numbers are
 *       identical to the Daily Sales &amp; Incentives page.</li>
 * </ul>
 *
 * <p>A section that fails to load is reported as {@code null} and logged; it never stops the email.
 * For that reason {@link #build} deliberately runs outside any surrounding transaction: swallowing a
 * failed query inside one would leave it rollback-only and make the final commit throw.
 */
@Service
public class MorningBriefService {

    private static final Logger log = LoggerFactory.getLogger(MorningBriefService.class);

    /** Statuses that never count as a sale (the set the dashboards and the P&amp;L use). */
    static final Set<OrderStatus> NOT_A_SALE =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);
    private static final Set<OrderStatus> DELIVERED = EnumSet.of(OrderStatus.DELIVERED);
    private static final Set<OrderStatus> FAILED = EnumSet.of(OrderStatus.CUSTOMER_REJECTED,
            OrderStatus.DELIVERY_FAILED, OrderStatus.RTO, OrderStatus.REDISPATCH);

    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Mobile stored when an order arrives without one; not a real customer. */
    private static final String PLACEHOLDER_MOBILE = "0000000000";
    static final String NO_ACCOUNT = "Cash / no account recorded";
    private static final int TOP = 3;
    private static final int IN_CLAUSE_CHUNK = 500;

    private final MorningBriefQueryRepository queries;
    private final BankAccountRepository bankAccounts;
    private final IncentiveTrackerService incentiveTracker;
    private final IncentiveRuleService incentiveRules;
    private final ReconciliationService reconciliation;
    private final CodAgingService codAging;
    private final OwnerSnapshotService ownerSnapshot;
    private final Clock clock;

    @Autowired
    public MorningBriefService(MorningBriefQueryRepository queries,
                               BankAccountRepository bankAccounts,
                               IncentiveTrackerService incentiveTracker,
                               IncentiveRuleService incentiveRules,
                               ReconciliationService reconciliation,
                               CodAgingService codAging,
                               OwnerSnapshotService ownerSnapshot) {
        this(queries, bankAccounts, incentiveTracker, incentiveRules, reconciliation, codAging, ownerSnapshot,
                Clock.system(BUSINESS_ZONE));
    }

    MorningBriefService(MorningBriefQueryRepository queries,
                        BankAccountRepository bankAccounts,
                        IncentiveTrackerService incentiveTracker,
                        IncentiveRuleService incentiveRules,
                        ReconciliationService reconciliation,
                        CodAgingService codAging,
                        OwnerSnapshotService ownerSnapshot,
                        Clock clock) {
        this.clock = clock;
        this.queries = queries;
        this.bankAccounts = bankAccounts;
        this.incentiveTracker = incentiveTracker;
        this.incentiveRules = incentiveRules;
        this.reconciliation = reconciliation;
        this.codAging = codAging;
        this.ownerSnapshot = ownerSnapshot;
    }

    /** Builds the morning email's figures for {@code day}. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MorningBrief build(LocalDate day) {
        LocalDateTime from = day.atStartOfDay();
        LocalDateTime to = day.plusDays(1).atStartOfDay();

        // The day itself and its comparisons: these are the email, so a failure here fails the run.
        Fold today = fold(queries.sales(from, to));
        LocalDate previous = day.minusDays(1);
        LocalDate lastWeek = day.minusDays(7);
        LocalDate monthStart = day.withDayOfMonth(1);
        Fold previousDay = fold(queries.sales(previous.atStartOfDay(), from));
        Fold sameWeekday = fold(queries.sales(lastWeek.atStartOfDay(), lastWeek.plusDays(1).atStartOfDay()));
        Fold month = fold(queries.sales(monthStart.atStartOfDay(), to));

        long orders = today.orders();
        BigDecimal sales = today.sales();
        Headline headline = new Headline(orders, sales, average(sales, orders),
                new Comparison(previous, previousDay.orders(), previousDay.sales()),
                new Comparison(lastWeek, sameWeekday.orders(), sameWeekday.sales()),
                monthStart, month.orders(), month.sales());

        List<ChannelRow> channels = List.of(
                channelRow("Portal", today.of(DashboardChannel.PORTAL)),
                channelRow("Shopify", today.of(DashboardChannel.SHOPIFY)),
                channelRow("Store (POS)", today.of(DashboardChannel.STORE)));
        ChannelRow total = new ChannelRow("Total", orders, sales, today.cod());

        Banks banks = optional("money received by bank", () -> banks(from, to));
        CashOnDelivery cod = optional("cash on delivery", () -> cashOnDelivery(day, today));

        // One tracker run feeds both the top salespeople and the incentive.
        IncentiveTracker.Response tracker = optional("daily sales tracker",
                () -> incentiveTracker.tracker(YearMonth.from(day)));
        List<PersonRow> top = tracker == null ? null : topSalespeople(tracker, day);
        Incentive incentive = tracker == null ? null : optional("daily incentive", () -> incentive(tracker, day));

        Highlights highlights = optional("highlights", () -> highlights(from, to, today));
        Dues dues = optional("money still to collect", this::dues);
        Attention attention = optional("work waiting", this::attention);

        boolean yesterday = day.equals(LocalDate.now(clock).minusDays(1));
        return new MorningBrief(day, yesterday, headline, channels, total, banks, cod, top, incentive,
                highlights, dues, attention);
    }

    // ---------------------------------------------------------------------------------------------
    // Sections
    // ---------------------------------------------------------------------------------------------

    private Banks banks(LocalDateTime from, LocalDateTime to) {
        Map<Long, long[]> payments = new LinkedHashMap<>();
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal awaiting = BigDecimal.ZERO;
        BigDecimal rejected = BigDecimal.ZERO;
        long count = 0;
        for (ReceiptRow row : queries.receipts(from, to)) {
            BigDecimal amount = nz(row.getAmount());
            long n = row.getPaymentCount() == null ? 0 : row.getPaymentCount();
            payments.computeIfAbsent(row.getAccountId(), k -> new long[1])[0] += n;
            amounts.merge(row.getAccountId(), amount, BigDecimal::add);
            total = total.add(amount);
            count += n;
            if (row.getVerification() == PaymentVerificationStatus.PENDING) {
                awaiting = awaiting.add(amount);
            } else if (row.getVerification() == PaymentVerificationStatus.REJECTED) {
                rejected = rejected.add(amount);
            }
        }

        Map<Long, String> names = new HashMap<>();
        List<Long> ids = amounts.keySet().stream().filter(java.util.Objects::nonNull).toList();
        if (!ids.isEmpty()) {
            for (BankAccount account : bankAccounts.findAllById(ids)) {
                names.put(account.getId(), account.getDisplayName());
            }
        }

        List<BankRow> rows = new ArrayList<>();
        for (Map.Entry<Long, BigDecimal> e : amounts.entrySet()) {
            if (e.getKey() == null) {
                continue;
            }
            rows.add(new BankRow(names.getOrDefault(e.getKey(), "Account #" + e.getKey()),
                    payments.get(e.getKey())[0], e.getValue()));
        }
        rows.sort(Comparator.comparing(BankRow::amount).reversed()
                .thenComparing(BankRow::account, String.CASE_INSENSITIVE_ORDER));
        if (amounts.containsKey(null)) {
            rows.add(new BankRow(NO_ACCOUNT, payments.get(null)[0], amounts.get(null)));
        }
        return new Banks(List.copyOf(rows), total, count, awaiting, rejected);
    }

    private CashOnDelivery cashOnDelivery(LocalDate day, Fold today) {
        BigDecimal received = BigDecimal.ZERO;
        long receivedCount = 0;
        for (CodReceivedRow row : queries.codReceivedOn(ReceivableType.COD_RECEIVABLE, day)) {
            received = received.add(nz(row.getAmount()));
            receivedCount += row.getReceivables() == null ? 0 : row.getReceivables();
        }
        return new CashOnDelivery(today.cod(), today.codOrders(), received, receivedCount);
    }

    private List<PersonRow> topSalespeople(IncentiveTracker.Response tracker, LocalDate day) {
        List<PersonRow> rows = new ArrayList<>();
        for (IncentiveTracker.PersonMonth person : tracker.people()) {
            IncentiveTracker.DayCell cell = cellOn(person, day);
            if (cell != null && cell.orders() > 0) {
                rows.add(new PersonRow(0, person.name(), cell.orders(), cell.sales(), cell.bonus()));
            }
        }
        rows.sort(Comparator.comparing(PersonRow::sales).reversed()
                .thenComparing(Comparator.comparingLong(PersonRow::orders).reversed())
                .thenComparing(PersonRow::name, String.CASE_INSENSITIVE_ORDER));
        List<PersonRow> ranked = new ArrayList<>();
        for (int i = 0; i < Math.min(TOP, rows.size()); i++) {
            PersonRow r = rows.get(i);
            ranked.add(new PersonRow(i + 1, r.name(), r.orders(), r.sales(), r.bonus()));
        }
        return List.copyOf(ranked);
    }

    private Incentive incentive(IncentiveTracker.Response tracker, LocalDate day) {
        Optional<RuleTimeline.Version> inForce = incentiveRules.timeline().inForce(day);
        boolean active = inForce.map(v -> !v.tiers().isEmpty()).orElse(false);
        List<Slab> slabs = active
                ? inForce.get().tiers().stream().map(t -> new Slab(t.minSales(), t.bonus())).toList()
                : List.of();

        int salespeople = 0;
        BigDecimal dayBonus = BigDecimal.ZERO;
        BigDecimal monthBonus = BigDecimal.ZERO;
        int monthBonusDays = 0;
        List<Earner> earners = new ArrayList<>();
        int lastIndex = day.getDayOfMonth() - 1;
        for (IncentiveTracker.PersonMonth person : tracker.people()) {
            if (person.active()) {
                salespeople++;
            }
            // Month to date, up to and including the report day (today's partial sales are not counted).
            for (int i = 0; i <= lastIndex && i < person.days().size(); i++) {
                BigDecimal bonus = person.days().get(i).bonus();
                if (bonus != null && bonus.signum() > 0) {
                    monthBonus = monthBonus.add(bonus);
                    monthBonusDays++;
                }
            }
            IncentiveTracker.DayCell cell = cellOn(person, day);
            if (cell != null && cell.bonus() != null && cell.bonus().signum() > 0) {
                earners.add(new Earner(person.name(), cell.orders(), cell.sales(), cell.slab(), cell.bonus()));
                dayBonus = dayBonus.add(cell.bonus());
            }
        }
        earners.sort(Comparator.comparing(Earner::bonus).reversed()
                .thenComparing(Comparator.comparing(Earner::sales).reversed())
                .thenComparing(Earner::name, String.CASE_INSENSITIVE_ORDER));
        return new Incentive(active, slabs, salespeople, List.copyOf(earners), dayBonus, monthBonus, monthBonusDays);
    }

    private Highlights highlights(LocalDateTime from, LocalDateTime to, Fold today) {
        Set<String> mobiles = new LinkedHashSet<>();
        for (String raw : queries.mobilesBetween(from, to, NOT_A_SALE)) {
            String mobile = raw == null ? "" : raw.trim();
            if (!mobile.isEmpty() && !PLACEHOLDER_MOBILE.equals(mobile)) {
                mobiles.add(mobile);
            }
        }
        Set<String> returning = new HashSet<>();
        List<String> list = new ArrayList<>(mobiles);
        for (int i = 0; i < list.size(); i += IN_CLAUSE_CHUNK) {
            returning.addAll(queries.mobilesBefore(from, NOT_A_SALE,
                    list.subList(i, Math.min(list.size(), i + IN_CLAUSE_CHUNK))));
        }
        long returningCustomers = returning.size();
        long newCustomers = Math.max(0, mobiles.size() - returningCustomers);

        List<ProductRow> best = new ArrayList<>();
        for (MorningBriefQueryRepository.ProductRow row : queries.productSales(from, to, NOT_A_SALE)) {
            if (best.size() == TOP) {
                break;
            }
            String name = row.getProductName() == null || row.getProductName().isBlank()
                    ? "Unnamed item" : row.getProductName().trim();
            best.add(new ProductRow(name, row.getUnits() == null ? 0 : row.getUnits(), nz(row.getSales())));
        }

        OutcomeRow delivered = firstOrEmpty(queries.movedInto(DELIVERED, from, to));
        OutcomeRow failed = firstOrEmpty(queries.movedInto(FAILED, from, to));
        return new Highlights(newCustomers, returningCustomers, List.copyOf(best),
                count(delivered), nz(delivered == null ? null : delivered.getAmount()),
                count(failed), nz(failed == null ? null : failed.getAmount()),
                today.lostOrders, today.lostValue);
    }

    private Dues dues() {
        CollectibleSummaryResponse c = reconciliation.collectibleSummary(codAging.aging());
        return new Dues(nz(c.customerOutstanding()), nz(c.codPendingFromCourier()), c.codOverSlaCount(),
                nz(c.codOverSlaAmount()), c.slaDays(), c.pendingClaims(), nz(c.pendingClaimsAmount()),
                nz(c.totalCollectible()));
    }

    private Attention attention() {
        OwnerSnapshotResponse s = ownerSnapshot.snapshot();
        return new Attention(s.approvalsWaiting(), s.paymentsPending(), s.failedDeliveries(), s.stuckShipments());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    private <T> T optional(String section, Supplier<T> loader) {
        try {
            return loader.get();
        } catch (RuntimeException e) {
            log.warn("Morning brief: could not load '{}': {}", section, e.getMessage());
            return null;
        }
    }

    private static IncentiveTracker.DayCell cellOn(IncentiveTracker.PersonMonth person, LocalDate day) {
        int index = day.getDayOfMonth() - 1;
        if (index >= 0 && index < person.days().size() && day.equals(person.days().get(index).date())) {
            return person.days().get(index);
        }
        return person.days().stream().filter(c -> day.equals(c.date())).findFirst().orElse(null);
    }

    private static ChannelRow channelRow(String label, Acc acc) {
        return new ChannelRow(label, acc.orders, acc.sales, acc.cod);
    }

    private static BigDecimal average(BigDecimal sales, long orders) {
        return orders == 0 ? BigDecimal.ZERO.setScale(2)
                : sales.divide(BigDecimal.valueOf(orders), 2, RoundingMode.HALF_UP);
    }

    private static OutcomeRow firstOrEmpty(List<OutcomeRow> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static long count(OutcomeRow row) {
        return row == null || row.getOrderCount() == null ? 0 : row.getOrderCount();
    }

    private static BigDecimal nz(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    static DashboardChannel channelOf(OrderSource source) {
        if (source == OrderSource.SHOPIFY) {
            return DashboardChannel.SHOPIFY;
        }
        if (source == OrderSource.STORE) {
            return DashboardChannel.STORE;
        }
        return DashboardChannel.PORTAL;
    }

    /** Folds grouped order rows into per-source totals, with rejected and cancelled set apart. */
    static Fold fold(List<SalesRow> rows) {
        Fold fold = new Fold();
        for (SalesRow row : rows) {
            long orders = row.getOrderCount() == null ? 0 : row.getOrderCount();
            BigDecimal sales = nz(row.getSales());
            if (NOT_A_SALE.contains(row.getOrderStatus())) {
                fold.lostOrders += orders;
                fold.lostValue = fold.lostValue.add(sales);
                continue;
            }
            Acc acc = fold.byChannel.get(channelOf(row.getSource()));
            acc.orders += orders;
            acc.sales = acc.sales.add(sales);
            acc.cod = acc.cod.add(nz(row.getCod()));
            acc.codOrders += row.getCodOrders() == null ? 0 : row.getCodOrders();
        }
        return fold;
    }

    /** Totals for one source. */
    static final class Acc {
        long orders;
        BigDecimal sales = BigDecimal.ZERO.setScale(2);
        BigDecimal cod = BigDecimal.ZERO.setScale(2);
        long codOrders;
    }

    /** Totals per source plus the rejected / cancelled orders that were left out. */
    static final class Fold {
        final Map<DashboardChannel, Acc> byChannel = new EnumMap<>(DashboardChannel.class);
        long lostOrders;
        BigDecimal lostValue = BigDecimal.ZERO.setScale(2);

        Fold() {
            byChannel.put(DashboardChannel.PORTAL, new Acc());
            byChannel.put(DashboardChannel.SHOPIFY, new Acc());
            byChannel.put(DashboardChannel.STORE, new Acc());
        }

        Acc of(DashboardChannel channel) {
            return byChannel.get(channel);
        }

        long orders() {
            return byChannel.values().stream().mapToLong(a -> a.orders).sum();
        }

        BigDecimal sales() {
            return byChannel.values().stream().map(a -> a.sales).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal cod() {
            return byChannel.values().stream().map(a -> a.cod).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .setScale(2, RoundingMode.HALF_UP);
        }

        long codOrders() {
            return byChannel.values().stream().mapToLong(a -> a.codOrders).sum();
        }
    }
}
