package com.shifa.oms.chatbot;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.chatbot.ChatbotQueryRepository.PersonProductStatusRow;
import com.shifa.oms.chatbot.ChatbotQueryRepository.PersonStatusRow;
import com.shifa.oms.chatbot.ChatbotQueryRepository.ProductBreakdownRow;
import com.shifa.oms.chatbot.ChatbotQueryRepository.ProductStatusRow;
import com.shifa.oms.chatbot.ChatbotQueryRepository.StatusRow;
import com.shifa.oms.chatbot.domain.AccountRef;
import com.shifa.oms.chatbot.domain.NameSuggester;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Topic;
import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.settings.BankAccount;
import com.shifa.oms.settings.BankAccountRepository;
import com.shifa.oms.settings.BankAccountType;
import com.shifa.oms.chatbot.domain.ParsedQuestion;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Breakdown;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Metric;
import com.shifa.oms.chatbot.domain.Period;
import com.shifa.oms.chatbot.domain.PersonRef;
import com.shifa.oms.chatbot.domain.ProductRef;
import com.shifa.oms.chatbot.domain.QuestionParser;
import com.shifa.oms.chatbot.dto.ChatbotAnswer;
import com.shifa.oms.chatbot.dto.ChatbotAnswer.Table;
import com.shifa.oms.dashboard.DashboardChannel;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.shifa.oms.chatbot.ChatbotFormat.capitalise;
import static com.shifa.oms.chatbot.ChatbotFormat.count;
import static com.shifa.oms.chatbot.ChatbotFormat.filterText;
import static com.shifa.oms.chatbot.ChatbotFormat.inr;
import static com.shifa.oms.chatbot.ChatbotFormat.percentChange;
import static com.shifa.oms.chatbot.ChatbotFormat.plural;
import static com.shifa.oms.chatbot.ChatbotFormat.sentenceList;
import static com.shifa.oms.chatbot.ChatbotFormat.spaced;
import static com.shifa.oms.chatbot.ChatbotFormat.stageLabel;
import static com.shifa.oms.chatbot.ChatbotFormat.statusLabel;

/**
 * The in-house Shifa assistant. Reads a plain-text question with
 * {@link QuestionParser}, runs fixed read-only aggregates against the order
 * tables and words the result. Nothing leaves the OMS: there is no AI model or
 * external service behind it, and it can only answer what the query catalogue
 * below can compute.
 *
 * <p>Numbers follow the dashboards: order counts include every order placed
 * (even cancelled), while sales and units leave out rejected, payment-rejected
 * and cancelled orders. Portal means neither Shopify nor in-shop (POS) sales.
 * When the question names something the bot cannot find, it says so instead of
 * answering for the whole business.
 */
@Service
public class ChatbotService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Kolkata");

    /** Statuses left out of sales and unit totals (same set the dashboards use). */
    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    private static final String UNATTRIBUTED = "Not attributed (e.g. Shopify)";

    private final ChatbotQueryRepository queries;
    private final UserRepository users;
    private final ProductRepository products;
    private final BankAccountRepository bankAccounts;
    private final MoneyAnswers money;
    private final LookupAnswers lookups;
    private final QuestionParser parser = new QuestionParser();
    private final Clock clock = Clock.system(BUSINESS_ZONE);

    public ChatbotService(ChatbotQueryRepository queries, UserRepository users, ProductRepository products,
                          BankAccountRepository bankAccounts, MoneyAnswers money, LookupAnswers lookups) {
        this.queries = queries;
        this.users = users;
        this.products = products;
        this.bankAccounts = bankAccounts;
        this.money = money;
        this.lookups = lookups;
    }

    /** Answers one question. Never throws for an unclear question; it replies with help instead. */
    @Transactional(readOnly = true)
    public ChatbotAnswer ask(String question) {
        ChatbotDirectory directory = loadDirectory();
        ParsedQuestion parsed = parser.parse(question, LocalDate.now(clock), directory.people(),
                directory.catalogue(), directory.accounts());
        return switch (parsed.kind()) {
            case HELP -> help(directory, true);
            case UNKNOWN -> help(directory, false);
            case QUERY -> answer(question, parsed, directory);
        };
    }

    // ------------------------------------------------------------------ directory

    private ChatbotDirectory loadDirectory() {
        List<PersonRef> people = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (User u : users.findByRoleInOrderByCreatedAtDescIdDesc(
                List.of(Role.ADMIN, Role.TEAM_LEAD, Role.SALESPERSON))) {
            people.add(new PersonRef(u.getId(), u.getFullName(), u.getRole() == Role.TEAM_LEAD));
            names.put(u.getId(), u.getFullName());
        }
        List<ProductRef> catalogue = new ArrayList<>();
        for (Product p : products.findAllByOrderByNameAsc()) {
            catalogue.add(new ProductRef(p.getId(), p.getName()));
        }
        // Only accounts payments can be received into: bank transfers, UPI QR codes, cash and other.
        List<AccountRef> accounts = new ArrayList<>();
        Map<Long, String> accountNames = new HashMap<>();
        for (BankAccount a : bankAccounts.findAllByOrderBySortOrderAscIdAsc()) {
            accountNames.put(a.getId(), a.getDisplayName());
            if (a.getAccountType() != BankAccountType.SHOPIFY_SETTLEMENT
                    && a.getAccountType() != BankAccountType.QUICSHIP_REMITTANCE) {
                accounts.add(new AccountRef(a.getId(), a.getDisplayName()));
            }
        }
        return new ChatbotDirectory(people, catalogue, accounts, names, accountNames);
    }

    // ------------------------------------------------------------------ scope + tallies

    /** The filters every query shares. {@code creatorIds == null} means any creator. */
    private record Scope(Period period, List<OrderSource> sources, List<Long> creatorIds, List<ProductRef> products) {

        boolean anyCreator() {
            return creatorIds == null;
        }

        Collection<Long> creatorParam() {
            return creatorIds == null ? List.of(-1L) : creatorIds;
        }

        boolean hasProducts() {
            return !products.isEmpty();
        }

        Collection<Long> productIds() {
            return products.stream().map(ProductRef::id).toList();
        }

        Collection<String> productNames() {
            return products.stream().map(p -> p.name().toLowerCase(Locale.ROOT)).toList();
        }

        Scope withSources(List<OrderSource> newSources) {
            return new Scope(period, newSources, creatorIds, products);
        }

        Scope withCreators(List<Long> ids) {
            return new Scope(period, sources, ids, products);
        }

        Scope withoutProducts() {
            return new Scope(period, sources, creatorIds, List.of());
        }
    }

    /** Running totals for one group of orders. */
    private static final class Tally {
        long orders;
        long cancelledOrders;
        long activeOrders;
        long units;
        long cancelledUnits;
        BigDecimal sales = BigDecimal.ZERO;

        void add(OrderStatus status, Long orderCount, BigDecimal amount, Long unitCount) {
            long c = orderCount == null ? 0 : orderCount;
            long u = unitCount == null ? 0 : unitCount;
            orders += c;
            if (NON_REVENUE.contains(status)) {
                cancelledOrders += c;
                cancelledUnits += u;
            } else {
                activeOrders += c;
                units += u;
                sales = sales.add(amount == null ? BigDecimal.ZERO : amount);
            }
        }
    }

    private static List<OrderSource> sourcesFor(DashboardChannel channel) {
        return Arrays.stream(OrderSource.values()).filter(channel::matches).toList();
    }

    /** Totals for the scope; with a product filter the figures are for those product lines only. */
    private Tally totals(Scope s) {
        Tally t = new Tally();
        if (s.hasProducts()) {
            for (ProductStatusRow r : queries.productTotals(s.period().start(), s.period().endExclusive(),
                    s.sources(), s.anyCreator(), s.creatorParam(), s.productIds(), s.productNames())) {
                t.add(r.getOrderStatus(), r.getOrderCount(), r.getAmount(), r.getUnits());
            }
        } else {
            for (StatusRow r : queries.totals(s.period().start(), s.period().endExclusive(),
                    s.sources(), s.anyCreator(), s.creatorParam())) {
                t.add(r.getOrderStatus(), r.getOrderCount(), r.getAmount(), null);
            }
        }
        return t;
    }

    /** {@link #totals} plus total units across all products when units were asked for without a product. */
    private Tally tallyFor(Scope s, Metric metric) {
        Tally t = totals(s);
        if (metric == Metric.UNITS && !s.hasProducts()) {
            for (ProductBreakdownRow r : queries.totalsByProduct(s.period().start(), s.period().endExclusive(),
                    s.sources(), s.anyCreator(), s.creatorParam())) {
                long u = r.getUnits() == null ? 0 : r.getUnits();
                if (NON_REVENUE.contains(r.getOrderStatus())) {
                    t.cancelledUnits += u;
                } else {
                    t.units += u;
                }
            }
        }
        return t;
    }

    /** The one figure a row of a breakdown shows for this metric. */
    private static String valueOf(Tally t, Metric metric) {
        return switch (metric) {
            case SALES -> inr(t.sales);
            case UNITS -> count(t.units);
            case ORDERS, SUMMARY -> count(t.orders);
        };
    }

    // ------------------------------------------------------------------ answering

    private ChatbotAnswer answer(String question, ParsedQuestion q, ChatbotDirectory dir) {
        // One specific order or customer.
        if (q.has(Topic.ORDER_LOOKUP)) {
            ChatbotSection s = q.orderCode() != null
                    ? lookups.byOrderNumber(q.orderCode(), dir)
                    : lookups.byReference(q.referenceNumber(), dir);
            return new ChatbotAnswer(true, String.join("\n", s.lines()), s.table(), lookupSuggestions(dir));
        }
        if (q.has(Topic.CUSTOMER_LOOKUP)) {
            ChatbotSection s = lookups.byMobile(q.customerMobile(), dir);
            if (s.lines().isEmpty()) {
                // Ten digits can also be a tracking number: try that before saying nothing was found.
                s = lookups.byReference(q.customerMobile(), dir);
            }
            return new ChatbotAnswer(true, String.join("\n", s.lines()), s.table(), lookupSuggestions(dir));
        }

        if (!q.unresolved().isEmpty()) {
            return unresolved(q, dir);
        }
        if (!q.ambiguousPeople().isEmpty()) {
            return ambiguous(q);
        }
        if (!q.ambiguousAccounts().isEmpty()) {
            return ambiguousAccounts(q);
        }

        if (q.has(Topic.ATTENTION)) {
            ChatbotSection s = money.attention();
            return new ChatbotAnswer(true, String.join("\n", s.lines()), s.table(),
                    List.of("Money still to collect", "Orders by status today", "Top 5 salespeople this month"));
        }

        List<Long> creators = creatorIds(q);
        List<OrderSource> sources = sourcesFor(q.channel());
        boolean needsSales = q.has(Topic.SALES);
        boolean needsMoney = q.has(Topic.RECEIPTS) || q.has(Topic.COD) || q.has(Topic.COD_COURIER);
        if (needsSales && !needsMoney) {
            return salesAnswer(question, q, dir, sources, creators);
        }

        // Several subjects in one question: money first, then the order / sales figures.
        List<String> lines = new ArrayList<>();
        Table table = null;
        if (q.has(Topic.RECEIPTS) || q.has(Topic.COD)) {
            ChatbotSection s = money.receiptsAndCod(q, dir, sources, creators);
            lines.addAll(s.lines());
            table = s.table();
        }
        if (q.has(Topic.COD_COURIER)) {
            ChatbotSection s = money.collectible(q);
            lines.addAll(s.lines());
            if (table == null) {
                table = s.table();
            }
        }
        List<String> suggestions = moneySuggestions(question, q, dir);
        if (needsSales) {
            ChatbotAnswer sales = salesAnswer(question, q, dir, sources, creators);
            lines.add(sales.answer());
            if (table == null) {
                table = sales.table();
            }
        }
        return new ChatbotAnswer(true, String.join("\n", lines), table, suggestions);
    }

    /** The sales / orders / units part of a question (the original behaviour, plus stages and periods). */
    private ChatbotAnswer salesAnswer(String question, ParsedQuestion q, ChatbotDirectory dir,
                                      List<OrderSource> sources, List<Long> creators) {
        Scope scope = new Scope(q.period(), sources, creators, q.products());
        Breakdown breakdown = q.breakdown();
        if (breakdown == Breakdown.NONE && q.people().size() > 1) {
            breakdown = Breakdown.PERSON;
        }
        if (breakdown == Breakdown.STATUS) {
            return byStatus(question, q, scope);
        }
        if (breakdown == Breakdown.NONE && q.statusGroup() != null) {
            return stageFigure(question, q, scope);
        }
        if (breakdown == Breakdown.NONE && q.allPeriods().size() > 1) {
            return byPeriod(question, q, scope);
        }
        return switch (breakdown) {
            case NONE -> figure(question, q, dir, scope);
            case PERSON -> byPerson(question, q, dir, scope);
            case PRODUCT -> byProduct(question, q, scope);
            case CHANNEL -> byChannel(question, q, scope);
            case STATUS -> byStatus(question, q, scope);
        };
    }

    private static List<String> moneySuggestions(String question, ParsedQuestion q, ChatbotDirectory dir) {
        List<String> out = new ArrayList<>();
        if (q.allPeriods().size() == 1 && q.has(Topic.RECEIPTS)) {
            out.add("Received in bank today, yesterday, this week and this month");
        }
        if (!q.has(Topic.COD)) {
            out.add("COD to collect on delivery today, yesterday and this month");
        }
        if (!q.has(Topic.COD_COURIER)) {
            out.add("Money still to collect");
        }
        if (!q.has(Topic.RECEIPTS)) {
            out.add("How much was received in bank today?");
        }
        out.add("What needs attention?");
        return out.stream().distinct().limit(4).toList();
    }

    private static List<String> lookupSuggestions(ChatbotDirectory dir) {
        return List.of("What needs attention?", "Orders by status today", "How much was received in bank today?");
    }

    /** Creator ids to filter on: the named people, plus their teams when a team was asked for. */
    private List<Long> creatorIds(ParsedQuestion q) {
        if (q.people().isEmpty()) {
            return null;
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (PersonRef p : q.people()) {
            ids.add(p.id());
            if (q.teamScope() && p.teamLead()) {
                ids.addAll(users.findIdsByTeamLeadId(p.id()));
            }
        }
        return new ArrayList<>(ids);
    }

    // ---- single figure

    private ChatbotAnswer figure(String question, ParsedQuestion q, ChatbotDirectory dir, Scope scope) {
        Tally t = tallyFor(scope, q.metric());
        String where = scopeText(q, true, true);
        String product = scope.hasProducts() ? productNames(scope) : null;
        List<String> lines = new ArrayList<>();

        // "Total sales by X, how many units of Y": both facts, kept apart so a product's line value is
        // never mistaken for the person's total sales.
        boolean combined = q.metric() == Metric.SUMMARY && scope.hasProducts();
        Scope overallScope = scope.withoutProducts();
        Tally overall = combined ? totals(overallScope) : t;

        switch (q.metric()) {
            case ORDERS -> {
                if (t.orders == 0) {
                    lines.add("No orders " + (product != null ? "included " + product : "were placed")
                            + " " + where + ".");
                } else {
                    lines.add(plural(t.orders, "order") + (product != null ? " included " + product : " placed")
                            + " " + where + ".");
                    lines.add("Sales value: " + inr(t.sales) + (product != null ? " (value of those lines)" : "") + ".");
                    includesCancelledNote(t, lines);
                }
            }
            case SALES -> {
                if (t.orders == 0) {
                    lines.add("No orders found " + (product != null ? "for " + product + " " : "") + where
                            + ", so there are no sales.");
                } else {
                    lines.add("Sales " + (product != null ? "of " + product + " " : "") + where + ": "
                            + inr(t.sales) + (product != null ? " (value of those lines)" : "")
                            + " from " + plural(t.activeOrders, "order") + ".");
                    leftOutNote(t, lines);
                }
            }
            case UNITS -> {
                String what = product != null ? product : "all products";
                if (t.orders == 0 && t.units == 0 && t.cancelledUnits == 0) {
                    lines.add("No units of " + what + " were sold " + where + ".");
                } else {
                    lines.add(plural(t.units, "unit") + " of " + what + " sold " + where
                            + (t.activeOrders > 0
                            ? ", across " + plural(t.activeOrders, "order") + " ("
                            + (product != null ? "line value " : "order value ") + inr(t.sales) + ")"
                            : "") + ".");
                    if (t.cancelledUnits > 0) {
                        lines.add(plural(t.cancelledUnits, "unit") + " on cancelled/rejected orders "
                                + (t.cancelledUnits == 1 ? "was" : "were") + " left out.");
                    }
                }
            }
            case SUMMARY -> {
                if (overall.orders == 0) {
                    lines.add("No orders " + where + ".");
                } else {
                    lines.add(summaryHeading(where) + ": " + plural(overall.orders, "order") + " placed, "
                            + inr(overall.sales) + " in sales.");
                    includesCancelledNote(overall, lines);
                }
                if (combined) {
                    lines.add(t.orders == 0
                            ? "No orders included " + product + " " + where + "."
                            : "Of that, " + product + ": " + plural(t.units, "unit") + " across "
                            + plural(t.activeOrders, "order") + " (line value " + inr(t.sales) + ").");
                }
            }
        }

        if (q.average() && overall.activeOrders > 0) {
            lines.add((product != null && !combined ? "Average value of those lines per order: " : "Average order value: ")
                    + average(overall) + " (" + inr(overall.sales) + " over "
                    + plural(overall.activeOrders, "order") + ").");
        }

        teamNote(q, combined ? overallScope : scope, lines);
        commonNotes(q, scope, lines);
        return reply(lines, null, suggestionsAfter(question, q, scope));
    }

    /** For a single team lead, also shows the figure including their whole team. */
    private void teamNote(ParsedQuestion q, Scope scope, List<String> lines) {
        if (q.people().size() != 1 || q.teamScope() || !q.people().get(0).teamLead() || q.breakdown() != Breakdown.NONE) {
            return;
        }
        PersonRef lead = q.people().get(0);
        List<Long> members = users.findIdsByTeamLeadId(lead.id());
        if (members.isEmpty()) {
            return;
        }
        List<Long> team = new ArrayList<>();
        team.add(lead.id());
        team.addAll(members);
        Tally t = tallyFor(scope.withCreators(team), q.metric());
        String value = switch (q.metric()) {
            case ORDERS -> plural(t.orders, "order");
            case SALES -> inr(t.sales);
            case UNITS -> plural(t.units, "unit");
            case SUMMARY -> plural(t.orders, "order") + ", " + inr(t.sales) + " in sales";
        };
        lines.add(lead.fullName() + " leads a team of " + plural(members.size(), "salesperson")
                + ". Including the whole team: " + value + ".");
    }

    // ---- lifecycle stage, status breakdown, several periods

    /** Per-status rows for the scope (restricted to a product's lines when one is named). */
    private List<StatusRow> statusRows(Scope s) {
        if (s.hasProducts()) {
            return new ArrayList<>(queries.productTotals(s.period().start(), s.period().endExclusive(), s.sources(),
                    s.anyCreator(), s.creatorParam(), s.productIds(), s.productNames()));
        }
        return new ArrayList<>(queries.totals(s.period().start(), s.period().endExclusive(), s.sources(),
                s.anyCreator(), s.creatorParam()));
    }

    /** "How many orders are Pending / Delivered / Returned ..." */
    private ChatbotAnswer stageFigure(String question, ParsedQuestion q, Scope scope) {
        if (scope.hasProducts()) {
            return new ChatbotAnswer(false, "I can't combine an order stage (like \"delivered\") with a specific product"
                    + " yet. Ask for the stage on its own, or for the product's units on their own.", null, List.of());
        }
        OrderStatusGroup group = q.statusGroup();
        String label = stageLabel(group);
        boolean reachedInPeriod = !q.periodDefaulted();
        List<StatusRow> rows = new ArrayList<>();
        if (reachedInPeriod) {
            rows.addAll(queries.movedInto(scope.period().start(), scope.period().endExclusive(), group.statuses(),
                    scope.sources(), scope.anyCreator(), scope.creatorParam()));
        } else {
            for (StatusRow r : queries.totals(scope.period().start(), scope.period().endExclusive(),
                    scope.sources(), scope.anyCreator(), scope.creatorParam())) {
                if (group.statuses().contains(r.getOrderStatus())) {
                    rows.add(r);
                }
            }
        }
        rows.sort(Comparator.comparing(StatusRow::getOrderStatus));

        long orders = 0;
        BigDecimal value = BigDecimal.ZERO;
        List<List<String>> tableRows = new ArrayList<>();
        for (StatusRow r : rows) {
            long c = r.getOrderCount() == null ? 0 : r.getOrderCount();
            BigDecimal a = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
            orders += c;
            value = value.add(a);
            tableRows.add(List.of(statusLabel(r.getOrderStatus()), count(c), inr(a)));
        }
        if (tableRows.size() > 1) {
            tableRows.add(List.of("Total", count(orders), inr(value)));
        }

        String filters = filterText(q, true, true);
        List<String> lines = new ArrayList<>();
        if (reachedInPeriod) {
            String when = spaced(filters, q.period().display());
            lines.add(orders == 0
                    ? "No orders reached the " + label + " stage " + when + "."
                    : plural(orders, "order") + " reached the " + label + " stage " + when + ", worth " + inr(value) + ".");
            if (orders > 0) {
                lines.add("Counted from the order history: orders whose status changed to " + label
                        + " in this period (each order counts once). The table shows where they are now.");
            }
        } else {
            String who = filters.isEmpty() ? "" : " " + filters;
            lines.add(orders == 0
                    ? "No orders are currently in the " + label + " stage" + who + "."
                    : plural(orders, "order") + (orders == 1 ? " is" : " are") + " currently in the " + label
                    + " stage" + who + ", worth " + inr(value) + ".");
        }
        commonNotes(q, scope, lines);
        Table table = orders == 0 ? null : new Table(List.of("Status", "Orders", "Order value"), tableRows);
        return reply(lines, table, suggestionsAfter(question, q, scope));
    }

    /** "Orders by status": where the orders placed in the period are now. */
    private ChatbotAnswer byStatus(String question, ParsedQuestion q, Scope scope) {
        Map<OrderStatusGroup, long[]> counts = new EnumMap<>(OrderStatusGroup.class);
        Map<OrderStatusGroup, BigDecimal> values = new EnumMap<>(OrderStatusGroup.class);
        long total = 0;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StatusRow r : statusRows(scope)) {
            OrderStatusGroup g = OrderStatusGroup.groupOf(r.getOrderStatus());
            if (g == null) {
                continue;
            }
            long c = r.getOrderCount() == null ? 0 : r.getOrderCount();
            BigDecimal a = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
            counts.computeIfAbsent(g, k -> new long[1])[0] += c;
            values.merge(g, a, BigDecimal::add);
            total += c;
            totalValue = totalValue.add(a);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> inline = new ArrayList<>();
        for (OrderStatusGroup g : OrderStatusGroup.values()) {
            long c = counts.containsKey(g) ? counts.get(g)[0] : 0;
            if (c > 0) {
                rows.add(List.of(stageLabel(g), count(c), inr(values.get(g))));
                inline.add(stageLabel(g) + " " + count(c));
            }
        }
        String where = scopeText(q, true, true);
        List<String> lines = new ArrayList<>();
        if (total == 0) {
            lines.add("No orders found " + where + ".");
        } else {
            lines.add("Orders placed " + where + ", by where they are now: " + String.join(", ", inline) + ".");
            rows.add(List.of("Total", count(total), inr(totalValue)));
            lines.add("Order value is the full order total, including orders that were later cancelled or rejected.");
        }
        commonNotes(q, scope, lines);
        return reply(lines, total == 0 ? null : new Table(List.of("Stage", "Orders", "Order value"), rows),
                suggestionsAfter(question, q, scope));
    }

    /** Several periods in one question: one row per period, plus a comparison when there are two. */
    private ChatbotAnswer byPeriod(String question, ParsedQuestion q, Scope base) {
        List<Period> periods = q.allPeriods();
        boolean showUnits = base.hasProducts() || q.metric() == Metric.UNITS;
        List<String> headers = new ArrayList<>(List.of("Period", "Orders placed", "Sales"));
        if (showUnits) {
            headers.add("Units");
        }
        if (q.average()) {
            headers.add("Avg order");
        }
        List<List<String>> rows = new ArrayList<>();
        List<Tally> tallies = new ArrayList<>();
        for (Period p : periods) {
            Tally t = tallyFor(new Scope(p, base.sources(), base.creatorIds(), base.products()), q.metric());
            tallies.add(t);
            List<String> row = new ArrayList<>(List.of(p.phrase(), count(t.orders), inr(t.sales)));
            if (showUnits) {
                row.add(count(t.units));
            }
            if (q.average()) {
                row.add(average(t));
            }
            rows.add(row);
        }

        String product = base.hasProducts() ? productNames(base) : null;
        String what = switch (q.metric()) {
            case ORDERS -> "Orders placed";
            case SALES -> "Sales";
            case UNITS -> "Units of " + (product != null ? product : "all products") + " sold";
            case SUMMARY -> "Orders and sales";
        };
        if (product != null && q.metric() != Metric.UNITS) {
            what += " (" + product + ")";
        }
        List<String> lines = new ArrayList<>();
        lines.add(spaced(what, filterText(q, true, true)) + ", by period:");

        if (periods.size() == 2) {
            Tally a = tallies.get(0);
            Tally b = tallies.get(1);
            Period pa = periods.get(0);
            Period pb = periods.get(1);
            String sentence = switch (q.metric()) {
                case ORDERS -> count(a.orders) + " vs " + count(b.orders) + " orders ("
                        + percentChange(BigDecimal.valueOf(a.orders), BigDecimal.valueOf(b.orders)) + ")";
                case UNITS -> count(a.units) + " vs " + count(b.units) + " units ("
                        + percentChange(BigDecimal.valueOf(a.units), BigDecimal.valueOf(b.units)) + ")";
                case SALES, SUMMARY -> inr(a.sales) + " vs " + inr(b.sales) + " in sales ("
                        + percentChange(a.sales, b.sales) + ")";
            };
            lines.add(capitalise(pa.phrase()) + " vs " + pb.phrase() + ": " + sentence + ".");
            LocalDate today = LocalDate.now(clock);
            if (pa.to() != null && !pa.to().isBefore(today) && pb.to() != null && pb.to().isBefore(today)) {
                lines.add("Note: " + pa.phrase() + " is not over yet, so it is being compared with a finished period.");
            }
        }
        long cancelled = tallies.stream().mapToLong(t -> t.cancelledOrders).sum();
        if (cancelled > 0) {
            lines.add("Order counts include " + plural(cancelled, "cancelled/rejected order") + "; sales"
                    + (showUnits ? " and units" : "") + " do not.");
        }
        commonNotes(q, base, lines);
        return reply(lines, new Table(headers, rows), suggestionsAfter(question, q, base));
    }

    private ChatbotAnswer ambiguousAccounts(ParsedQuestion q) {
        List<String> names = q.ambiguousAccounts().stream().map(AccountRef::name).distinct().toList();
        String text = "That matches more than one account: " + sentenceList(names) + ". Which one do you mean?";
        List<String> suggestions = names.stream().map(n -> "Received in " + n + " today").limit(4).toList();
        return new ChatbotAnswer(false, text, null, suggestions);
    }

    /** Sales divided by the orders that count towards sales. */
    private static String average(Tally t) {
        if (t.activeOrders == 0) {
            return "-";
        }
        return inr(t.sales.divide(BigDecimal.valueOf(t.activeOrders), 2, RoundingMode.HALF_UP));
    }

    // ---- breakdown by salesperson

    private ChatbotAnswer byPerson(String question, ParsedQuestion q, ChatbotDirectory dir, Scope scope) {
        Map<Long, Tally> byId = new LinkedHashMap<>();
        if (scope.hasProducts()) {
            for (PersonProductStatusRow r : queries.productTotalsByPerson(scope.period().start(),
                    scope.period().endExclusive(), scope.sources(), scope.anyCreator(), scope.creatorParam(),
                    scope.productIds(), scope.productNames())) {
                byId.computeIfAbsent(r.getPersonId(), k -> new Tally())
                        .add(r.getOrderStatus(), r.getOrderCount(), r.getAmount(), r.getUnits());
            }
        } else {
            for (PersonStatusRow r : queries.totalsByPerson(scope.period().start(), scope.period().endExclusive(),
                    scope.sources(), scope.anyCreator(), scope.creatorParam())) {
                byId.computeIfAbsent(r.getPersonId(), k -> new Tally())
                        .add(r.getOrderStatus(), r.getOrderCount(), r.getAmount(), null);
            }
        }

        boolean showUnits = scope.hasProducts();
        // Orders with no creator (e.g. Shopify) are not a person: they never take part in the ranking,
        // but they stay in the table and the total so the figures still add up.
        Tally unattributed = byId.remove(null);
        List<Map.Entry<Long, Tally>> ranked = new ArrayList<>(byId.entrySet());
        Comparator<Map.Entry<Long, Tally>> order = switch (q.metric()) {
            case ORDERS -> Comparator.comparingLong(e -> e.getValue().orders);
            case UNITS -> showUnits
                    ? Comparator.comparingLong(e -> e.getValue().units)
                    : Comparator.comparing(e -> e.getValue().sales);
            case SALES, SUMMARY -> Comparator.comparing(e -> e.getValue().sales);
        };
        Comparator<Map.Entry<Long, Tally>> byName = Comparator.comparing(e -> personLabel(e.getKey(), dir));
        ranked.sort((q.ascending() ? order : order.reversed()).thenComparing(byName));

        List<String> headers = new ArrayList<>(List.of("Salesperson", "Orders placed", "Sales"));
        if (showUnits) {
            headers.add("Units");
        }
        if (q.average()) {
            headers.add("Avg order");
        }
        List<List<String>> rows = new ArrayList<>();
        Tally total = new Tally();
        List<Tally> everyone = new ArrayList<>(byId.values());
        if (unattributed != null) {
            everyone.add(unattributed);
        }
        for (Tally t : everyone) {
            total.orders += t.orders;
            total.cancelledOrders += t.cancelledOrders;
            total.activeOrders += t.activeOrders;
            total.units += t.units;
            total.sales = total.sales.add(t.sales);
        }
        for (Map.Entry<Long, Tally> e : ranked.subList(0, Math.min(q.topN(), ranked.size()))) {
            rows.add(row(personLabel(e.getKey(), dir), e.getValue(), showUnits, q.average()));
        }
        if (unattributed != null) {
            rows.add(row(UNATTRIBUTED, unattributed, showUnits, q.average()));
        }
        boolean hasData = !ranked.isEmpty() || unattributed != null;
        if (hasData) {
            rows.add(row("Total (" + plural(ranked.size(), "person").replace("persons", "people") + ")", total,
                    showUnits, q.average()));
        }

        String where = scopeText(q, true, false);
        List<String> lines = new ArrayList<>();
        if (!hasData) {
            lines.add("No orders found " + where + ".");
        } else if (ranked.isEmpty()) {
            lines.add("None of these orders belong to a salesperson " + where
                    + " (Shopify orders are not attributed to one).");
        } else {
            Map.Entry<Long, Tally> first = ranked.get(0);
            String metricWords = switch (q.metric()) {
                case ORDERS -> plural(first.getValue().orders, "order");
                case UNITS -> showUnits ? plural(first.getValue().units, "unit") : inr(first.getValue().sales) + " in sales";
                case SALES, SUMMARY -> inr(first.getValue().sales) + " in sales";
            };
            lines.add(personLabel(first.getKey(), dir) + (q.ascending() ? " is lowest " : " leads ") + where
                    + " with " + metricWords + ".");
            if (ranked.size() > q.topN()) {
                lines.add("Showing " + (q.ascending() ? "the lowest " : "the top ") + q.topN() + " of " + ranked.size() + ".");
            }
            if (total.cancelledOrders > 0) {
                lines.add("Order counts include " + plural(total.cancelledOrders, "cancelled/rejected order")
                        + "; sales" + (showUnits ? " and units" : "") + " do not.");
            }
        }
        commonNotes(q, scope, lines);
        return reply(lines, hasData ? new Table(headers, rows) : null, suggestionsAfter(question, q, scope));
    }

    private String personLabel(Long id, ChatbotDirectory dir) {
        if (id == null) {
            return UNATTRIBUTED;
        }
        return dir.staffNames().getOrDefault(id, "User #" + id);
    }

    private static List<String> row(String label, Tally t, boolean showUnits, boolean showAverage) {
        List<String> cells = new ArrayList<>(List.of(label, count(t.orders), inr(t.sales)));
        if (showUnits) {
            cells.add(count(t.units));
        }
        if (showAverage) {
            cells.add(average(t));
        }
        return cells;
    }

    // ---- breakdown by product

    private ChatbotAnswer byProduct(String question, ParsedQuestion q, Scope scope) {
        Set<String> wanted = new LinkedHashSet<>();
        for (ProductRef p : scope.products()) {
            wanted.add(p.name().toLowerCase(Locale.ROOT));
        }
        Map<String, Tally> byName = new LinkedHashMap<>();
        Map<String, String> display = new HashMap<>();
        for (ProductBreakdownRow r : queries.totalsByProduct(scope.period().start(), scope.period().endExclusive(),
                scope.sources(), scope.anyCreator(), scope.creatorParam())) {
            String key = r.getProductName() == null ? "" : r.getProductName().trim().toLowerCase(Locale.ROOT);
            if (!wanted.isEmpty() && !wanted.contains(key)) {
                continue;
            }
            display.putIfAbsent(key, r.getProductName());
            byName.computeIfAbsent(key, k -> new Tally())
                    .add(r.getOrderStatus(), r.getOrderCount(), r.getAmount(), r.getUnits());
        }

        List<Map.Entry<String, Tally>> ranked = new ArrayList<>(byName.entrySet());
        Comparator<Map.Entry<String, Tally>> order = switch (q.metric()) {
            case SALES -> Comparator.comparing(e -> e.getValue().sales);
            case ORDERS -> Comparator.comparingLong(e -> e.getValue().orders);
            case UNITS, SUMMARY -> Comparator.comparingLong(e -> e.getValue().units);
        };
        Comparator<Map.Entry<String, Tally>> byProductName = Comparator.comparing(e -> display.get(e.getKey()).toLowerCase(Locale.ROOT));
        ranked.sort((q.ascending() ? order : order.reversed()).thenComparing(byProductName));

        List<List<String>> rows = new ArrayList<>();
        long cancelled = 0;
        for (Map.Entry<String, Tally> e : byName.entrySet()) {
            cancelled += e.getValue().cancelledOrders;
        }
        for (Map.Entry<String, Tally> e : ranked.subList(0, Math.min(q.topN(), ranked.size()))) {
            Tally t = e.getValue();
            rows.add(List.of(display.get(e.getKey()), count(t.units), count(t.orders), inr(t.sales)));
        }

        String where = scopeText(q, true, true);
        List<String> lines = new ArrayList<>();
        if (ranked.isEmpty()) {
            lines.add("No product sales found " + where + ".");
        } else {
            Map.Entry<String, Tally> first = ranked.get(0);
            String value = switch (q.metric()) {
                case SALES -> inr(first.getValue().sales) + " in sales";
                case ORDERS -> plural(first.getValue().orders, "order");
                case UNITS, SUMMARY -> plural(first.getValue().units, "unit");
            };
            lines.add((q.ascending() ? "Slowest-selling product " : "Best-selling product ") + where + ": "
                    + display.get(first.getKey()) + " with " + value + ".");
            if (ranked.size() > q.topN()) {
                lines.add("Showing " + (q.ascending() ? "the slowest " : "the top ") + q.topN() + " of " + ranked.size() + ".");
            }
            lines.add("Product sales are line values before any order-level discount, so they can differ slightly"
                    + " from order totals." + (cancelled > 0
                    ? " Order counts include " + plural(cancelled, "cancelled/rejected order") + "; units and sales do not."
                    : ""));
        }
        commonNotes(q, scope, lines);
        Table table = ranked.isEmpty() ? null
                : new Table(List.of("Product", "Units sold", "Orders", "Sales"), rows);
        return reply(lines, table, suggestionsAfter(question, q, scope));
    }

    // ---- breakdown by channel

    private ChatbotAnswer byChannel(String question, ParsedQuestion q, Scope scope) {
        boolean generic = q.compareChannels().isEmpty();
        List<DashboardChannel> channels = generic
                ? List.of(DashboardChannel.PORTAL, DashboardChannel.SHOPIFY, DashboardChannel.STORE, DashboardChannel.ALL)
                : q.compareChannels();

        boolean showUnits = scope.hasProducts() || q.metric() == Metric.UNITS;
        List<String> headers = new ArrayList<>(List.of("Channel", "Orders placed", "Sales"));
        if (showUnits) {
            headers.add("Units");
        }
        if (q.average()) {
            headers.add("Avg order");
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> inline = new ArrayList<>();
        long cancelled = 0;
        for (DashboardChannel channel : channels) {
            Tally t = tallyFor(scope.withSources(sourcesFor(channel)), q.metric());
            if (generic && channel == DashboardChannel.STORE && t.orders == 0) {
                continue;
            }
            if (channel == DashboardChannel.ALL) {
                cancelled = t.cancelledOrders;
            }
            rows.add(row(channelLabel(channel), t, showUnits, q.average()));
            inline.add(channelLabel(channel) + " " + valueOf(t, q.metric()));
        }

        String what = switch (q.metric()) {
            case SALES -> "Sales";
            case UNITS -> "Units of " + (scope.hasProducts() ? productNames(scope) : "all products") + " sold";
            case ORDERS, SUMMARY -> "Orders placed";
        };
        if (scope.hasProducts() && (q.metric() == Metric.ORDERS || q.metric() == Metric.SUMMARY)) {
            what = "Orders with " + productNames(scope);
        }
        List<String> lines = new ArrayList<>();
        lines.add(what + " " + scopeText(q, false, true) + ": " + String.join(", ", inline) + ".");
        if (cancelled > 0) {
            lines.add("Order counts include " + plural(cancelled, "cancelled/rejected order") + "; sales"
                    + (showUnits ? " and units" : "") + " do not.");
        }
        commonNotes(q, scope, lines);
        return reply(lines, new Table(headers, rows), suggestionsAfter(question, q, scope));
    }

    private static String channelLabel(DashboardChannel channel) {
        return switch (channel) {
            case PORTAL -> "Portal";
            case SHOPIFY -> "Shopify";
            case STORE -> "In-shop (POS)";
            case ALL -> "All sources";
        };
    }

    // ------------------------------------------------------------------ wording

    /** "by Sameer Khan from Shopify today (31 Oct 2026)", with any part optional. */
    private static String scopeText(ParsedQuestion q, boolean includeChannel, boolean includePeople) {
        String who = "";
        if (includePeople && !q.people().isEmpty()) {
            List<String> names = q.people().stream().map(PersonRef::fullName).toList();
            boolean team = q.teamScope() && q.people().stream().anyMatch(PersonRef::teamLead);
            who = "by " + sentenceList(names) + (team ? (names.size() == 1 ? "'s team" : " (with their teams)") : "");
        }
        String where = "";
        if (includeChannel) {
            where = switch (q.channel()) {
                case ALL -> "";
                case PORTAL -> "from the portal";
                case SHOPIFY -> "from Shopify";
                case STORE -> "from in-shop (POS) sales";
            };
        }
        return spaced(who, where, q.period().display());
    }

    private static String productNames(Scope scope) {
        return sentenceList(scope.products().stream().map(ProductRef::name).toList());
    }

    /** "Today (31 Oct 2026)" stands alone; "by X ..." / "from Y ..." needs a noun in front. */
    private static String summaryHeading(String where) {
        return where.startsWith("by ") || where.startsWith("from ") ? "Totals " + where : capitalise(where);
    }

    private static void includesCancelledNote(Tally t, List<String> lines) {
        if (t.cancelledOrders > 0) {
            lines.add("This includes " + plural(t.cancelledOrders, "cancelled/rejected order") + ", which "
                    + (t.cancelledOrders == 1 ? "is" : "are") + " not counted in sales.");
        }
    }

    private static void leftOutNote(Tally t, List<String> lines) {
        if (t.cancelledOrders > 0) {
            lines.add(plural(t.cancelledOrders, "cancelled/rejected order") + " "
                    + (t.cancelledOrders == 1 ? "was" : "were") + " left out.");
        }
    }

    /** Notes every answer may need: assumed period, multiple product matches, ignored words. */
    private static void commonNotes(ParsedQuestion q, Scope scope, List<String> lines) {
        if (q.periodDefaulted()) {
            lines.add("No date was mentioned, so this covers all time. Add \"today\", \"this month\" or a month name"
                    + " to narrow it down.");
        }
        if (!q.extraPeriods().isEmpty() && q.breakdown() != Breakdown.NONE) {
            lines.add("I used only the first period (" + q.period().phrase() + ") for this breakdown. Ask for the"
                    + " others separately.");
        }
        if (scope.products().size() > 1) {
            lines.add("Matched " + scope.products().size() + " products: " + productNames(scope) + ".");
        }
        if (!q.ignored().isEmpty()) {
            lines.add("I didn't use: " + String.join(", ", q.ignored().stream().map(w -> "\"" + w + "\"").toList()) + ".");
        }
    }

    private static List<String> suggestionsAfter(String question, ParsedQuestion q, Scope scope) {
        String base = question.replaceAll("[?.!\\s]+$", "").trim();
        List<String> out = new ArrayList<>();
        // Re-asking with a period tacked on only reads sensibly for a short question.
        if (q.periodDefaulted() && base.length() <= 70) {
            out.add(base + " today");
            out.add(base + " this month");
        }
        if (scope.products().size() > 1 && q.breakdown() == Breakdown.NONE) {
            out.add(base + " by product");
        }
        if (q.breakdown() != Breakdown.PERSON) {
            out.add("Top 5 salespeople this month");
        }
        if (q.breakdown() != Breakdown.PRODUCT) {
            out.add("Best selling products this month");
        }
        return out.stream().distinct().limit(4).toList();
    }

    private static ChatbotAnswer reply(List<String> lines, Table table, List<String> suggestions) {
        return new ChatbotAnswer(true, String.join("\n", lines), table, suggestions);
    }

    // ------------------------------------------------------------------ clarifications + help

    private ChatbotAnswer unresolved(ParsedQuestion q, ChatbotDirectory dir) {
        List<String> quoted = q.unresolved().stream().map(w -> "\u201C" + w + "\u201D").toList();
        String text = "I couldn't find a salesperson or product called " + sentenceList(quoted)
                + ", so I haven't guessed a number. Please check the spelling.";
        List<String> suggestions = new ArrayList<>();
        for (NameSuggester.Suggestion s : NameSuggester.suggest(q.unresolved().get(0), dir.people(), dir.catalogue(), 3)) {
            suggestions.add(s.person()
                    ? "Total sales by " + s.name()
                    : "How many units of " + s.name() + " were sold?");
        }
        if (!suggestions.isEmpty()) {
            text += " Did you mean one of these?";
        }
        return new ChatbotAnswer(false, text, null, suggestions);
    }

    private ChatbotAnswer ambiguous(ParsedQuestion q) {
        List<String> names = q.ambiguousPeople().stream().map(PersonRef::fullName).distinct().toList();
        String text = "That name matches more than one person: " + sentenceList(names) + ". Which one do you mean?";
        List<String> suggestions = names.stream().map(n -> "Total sales by " + n).limit(4).toList();
        return new ChatbotAnswer(false, text, null, suggestions);
    }

    private ChatbotAnswer help(ChatbotDirectory dir, boolean friendly) {
        String text = (friendly ? "" : "I didn't understand that question. ")
                + "I answer from the Shifa database only; nothing is sent outside Shifa. I can tell you:\n"
                + "- orders, sales and units, by channel (Portal, Shopify, in-shop), period, salesperson or product,"
                + " and rankings such as top 5 salespeople or best selling products;\n"
                + "- the status of an order, from its order number or tracking number, or a customer's orders from"
                + " their mobile number;\n"
                + "- money received in each bank or QR account, and cash on delivery to collect, for today, yesterday,"
                + " this week, this month and more;\n"
                + "- orders by stage, money still to collect, and what needs your attention.";

        String person = dir.people().stream().filter(PersonRef::teamLead).map(PersonRef::fullName).findFirst()
                .orElseGet(() -> dir.people().stream().map(PersonRef::fullName).findFirst().orElse(null));
        String product = dir.catalogue().stream().map(ProductRef::name).findFirst().orElse(null);
        String account = dir.accounts().stream().map(AccountRef::name).findFirst().orElse(null);
        String newestOrder = queries.findFirstByOrderByCreatedAtDescIdDesc().map(OrderEntity::getOrderCode).orElse(null);

        List<String> examples = new ArrayList<>();
        examples.add("How many orders were placed today from Portal, Shopify and all sources?");
        examples.add(account != null
                ? "How much was received in " + account + " today, yesterday and this week?"
                : "How much was received in bank today?");
        examples.add("COD to collect on delivery today, yesterday, this week and this month");
        if (newestOrder != null) {
            examples.add("What is the status of order " + newestOrder + "?");
        }
        if (person != null) {
            examples.add("Total sales by " + person + " this month");
        }
        if (product != null) {
            examples.add("How many units of " + product + " were sold this month?");
        }
        examples.add("What needs attention?");
        examples.add("Orders by status today");
        return new ChatbotAnswer(friendly, text, null, examples);
    }
}
