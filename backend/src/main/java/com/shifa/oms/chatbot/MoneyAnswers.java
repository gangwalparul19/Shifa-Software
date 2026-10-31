package com.shifa.oms.chatbot;

import com.shifa.oms.chatbot.ChatbotQueryRepository.ReceiptRow;
import com.shifa.oms.chatbot.ChatbotQueryRepository.StatusRow;
import com.shifa.oms.chatbot.domain.AccountRef;
import com.shifa.oms.chatbot.domain.ParsedQuestion;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Topic;
import com.shifa.oms.chatbot.domain.Period;
import com.shifa.oms.chatbot.dto.ChatbotAnswer.Table;
import com.shifa.oms.dashboard.OwnerSnapshotService;
import com.shifa.oms.dashboard.dto.OwnerSnapshotResponse;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.reconciliation.CodAgingService;
import com.shifa.oms.reconciliation.ReconciliationService;
import com.shifa.oms.reconciliation.dto.CodAgingResponse;
import com.shifa.oms.reconciliation.dto.CollectibleSummaryResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.shifa.oms.chatbot.ChatbotFormat.count;
import static com.shifa.oms.chatbot.ChatbotFormat.filterText;
import static com.shifa.oms.chatbot.ChatbotFormat.inr;
import static com.shifa.oms.chatbot.ChatbotFormat.plural;
import static com.shifa.oms.chatbot.ChatbotFormat.sentenceList;

/**
 * Money questions for the chatbot: what was received in each bank / QR account,
 * the cash on delivery still to be collected, the courier backlog, and the
 * owner's "what needs attention" snapshot.
 *
 * <p>Every figure reuses an existing definition so the chat agrees with the app:
 * receipts are the Settlement Center's "OMS booked" (payments by capture time and
 * receiving account), COD is the dashboard's "COD due" (cash-on-delivery amount on
 * orders placed in the period, cancelled / rejected ones left out), the backlog is
 * the Reconciliation page's collectible summary, and attention is the owner
 * snapshot.
 */
@Component
public class MoneyAnswers {

    private static final Set<OrderStatus> NON_REVENUE = EnumSet.of(
            OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    private final ChatbotQueryRepository queries;
    private final ReconciliationService reconciliation;
    private final CodAgingService codAging;
    private final OwnerSnapshotService ownerSnapshot;

    public MoneyAnswers(ChatbotQueryRepository queries, ReconciliationService reconciliation,
                        CodAgingService codAging, OwnerSnapshotService ownerSnapshot) {
        this.queries = queries;
        this.reconciliation = reconciliation;
        this.codAging = codAging;
        this.ownerSnapshot = ownerSnapshot;
    }

    // ------------------------------------------------------------------ receipts + COD

    /** Money captured when orders were punched, in one account. */
    private static final class Received {
        BigDecimal booked = BigDecimal.ZERO;
        BigDecimal verified = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;
        BigDecimal rejected = BigDecimal.ZERO;
        BigDecimal notReviewed = BigDecimal.ZERO;
        long payments;

        void add(ReceiptRow row) {
            BigDecimal amount = row.getAmount() == null ? BigDecimal.ZERO : row.getAmount();
            booked = booked.add(amount);
            payments += row.getPaymentCount() == null ? 0 : row.getPaymentCount();
            if (row.getVerification() == null) {
                notReviewed = notReviewed.add(amount);
            } else {
                switch (row.getVerification()) {
                    case VERIFIED -> verified = verified.add(amount);
                    case PENDING -> pending = pending.add(amount);
                    case REJECTED -> rejected = rejected.add(amount);
                }
            }
        }

        void add(Received other) {
            booked = booked.add(other.booked);
            verified = verified.add(other.verified);
            pending = pending.add(other.pending);
            rejected = rejected.add(other.rejected);
            notReviewed = notReviewed.add(other.notReviewed);
            payments += other.payments;
        }
    }

    /** Cash on delivery due on orders placed in a period. */
    private record CodDue(BigDecimal amount, long orders) {
    }

    private Map<Long, Received> receipts(Period p, List<OrderSource> sources, List<Long> creators) {
        Map<Long, Received> byAccount = new LinkedHashMap<>();
        for (ReceiptRow row : queries.receipts(p.start(), p.endExclusive(), sources, creators == null,
                creators == null ? List.of(-1L) : creators)) {
            byAccount.computeIfAbsent(row.getAccountId(), k -> new Received()).add(row);
        }
        return byAccount;
    }

    private CodDue codDue(Period p, List<OrderSource> sources, List<Long> creators) {
        BigDecimal amount = BigDecimal.ZERO;
        long orders = 0;
        for (StatusRow row : queries.codTotals(p.start(), p.endExclusive(), sources, creators == null,
                creators == null ? List.of(-1L) : creators)) {
            if (NON_REVENUE.contains(row.getOrderStatus())) {
                continue;
            }
            amount = amount.add(row.getAmount() == null ? BigDecimal.ZERO : row.getAmount());
            orders += row.getOrderCount() == null ? 0 : row.getOrderCount();
        }
        return new CodDue(amount, orders);
    }

    /** Receipts and/or COD for the periods in the question. */
    ChatbotSection receiptsAndCod(ParsedQuestion q, ChatbotDirectory dir, List<OrderSource> sources,
                                  List<Long> creators) {
        boolean wantReceipts = q.has(Topic.RECEIPTS);
        boolean wantCod = q.has(Topic.COD);
        Set<Long> selected = new LinkedHashSet<>();
        for (AccountRef a : q.accounts()) {
            selected.add(a.id());
        }
        List<String> lines = new ArrayList<>();

        // A reference like "bank 3" that matches no account must never fall back to "all accounts".
        if (!q.unresolvedAccounts().isEmpty()) {
            lines.add(unknownAccounts(q, dir));
            if (selected.isEmpty() && !wantCod) {
                return ChatbotSection.ofLines(lines);
            }
            if (selected.isEmpty()) {
                wantReceipts = false;
            }
        }

        List<Period> periods = q.allPeriods();
        ChatbotSection body = periods.size() == 1
                ? singlePeriod(q, dir, sources, creators, periods.get(0), selected, wantReceipts, wantCod)
                : severalPeriods(q, dir, sources, creators, periods, selected, wantReceipts, wantCod);
        lines.addAll(body.lines());
        return new ChatbotSection(lines, body.table());
    }

    private ChatbotSection singlePeriod(ParsedQuestion q, ChatbotDirectory dir, List<OrderSource> sources,
                                        List<Long> creators, Period p, Set<Long> selected, boolean wantReceipts,
                                        boolean wantCod) {
        List<String> lines = new ArrayList<>();
        Table table = null;
        String filters = filterText(q, true, true);

        if (wantReceipts) {
            Map<Long, Received> all = receipts(p, sources, creators);
            List<Long> ids = new ArrayList<>();
            if (!selected.isEmpty()) {
                ids.addAll(selected);
            } else {
                for (AccountRef a : dir.accounts()) {
                    if (all.containsKey(a.id()) && all.get(a.id()).booked.signum() != 0) {
                        ids.add(a.id());
                    }
                }
                for (Long accountId : all.keySet()) {
                    if (accountId != null && !ids.contains(accountId) && all.get(accountId).booked.signum() != 0) {
                        ids.add(accountId);
                    }
                }
                if (all.containsKey(null) && all.get(null).booked.signum() != 0) {
                    ids.add(null);
                }
            }

            Received total = new Received();
            Map<Long, Received> rows = new LinkedHashMap<>();
            for (Long id : ids) {
                Received r = all.getOrDefault(id, new Received());
                rows.put(id, r);
                total.add(r);
            }

            String where = selected.isEmpty()
                    ? "bank / QR accounts"
                    : sentenceList(selected.stream().map(dir::accountName).toList());
            if (total.payments == 0 && total.booked.signum() == 0) {
                lines.add("No payments were received in " + where + " " + spaceJoin(filters, p.display()) + ".");
            } else if (selected.size() == 1) {
                lines.add(where + " received " + inr(total.booked) + " " + spaceJoin(filters, p.display())
                        + " across " + plural(total.payments, "payment") + ".");
            } else {
                lines.add("Received in " + where + " " + spaceJoin(filters, p.display()) + ": " + inr(total.booked)
                        + " across " + plural(total.payments, "payment") + ".");
            }
            if (total.booked.signum() != 0) {
                String split = splitText(total);
                if (!split.isEmpty()) {
                    lines.add(split);
                }
                if (rows.containsKey(null)) {
                    lines.add("Payments with no receiving account recorded (older orders, cash or Shopify) are shown as "
                            + ChatbotDirectory.UNASSIGNED_ACCOUNT + ".");
                }
                table = receiptTable(rows, total, dir);
            }
        }

        if (wantCod) {
            CodDue cod = codDue(p, sources, creators);
            lines.add(cod.orders() == 0
                    ? "No cash on delivery is due on orders placed " + spaceJoin(filters, p.display()) + "."
                    : "COD to collect on delivery for orders placed " + spaceJoin(filters, p.display()) + ": "
                    + inr(cod.amount()) + " across " + plural(cod.orders(), "order") + ".");
            lines.add("Cancelled and rejected orders are left out.");
        }
        return new ChatbotSection(lines, table);
    }

    private ChatbotSection severalPeriods(ParsedQuestion q, ChatbotDirectory dir, List<OrderSource> sources,
                                          List<Long> creators, List<Period> periods, Set<Long> selected,
                                          boolean wantReceipts, boolean wantCod) {
        boolean perAccount = wantReceipts && selected.size() > 1;
        List<String> headers = new ArrayList<>();
        headers.add("Period");
        if (wantReceipts) {
            headers.add(selected.size() == 1 ? dir.accountName(selected.iterator().next()) + " received"
                    : "Received in bank");
            if (perAccount) {
                selected.forEach(id -> headers.add(dir.accountName(id)));
            }
        }
        if (wantCod) {
            headers.add("COD to collect");
        }

        List<List<String>> rows = new ArrayList<>();
        for (Period p : periods) {
            List<String> row = new ArrayList<>();
            row.add(p.phrase());
            if (wantReceipts) {
                Map<Long, Received> all = receipts(p, sources, creators);
                BigDecimal sum = BigDecimal.ZERO;
                for (Map.Entry<Long, Received> e : all.entrySet()) {
                    if (selected.isEmpty() || selected.contains(e.getKey())) {
                        sum = sum.add(e.getValue().booked);
                    }
                }
                row.add(inr(sum));
                if (perAccount) {
                    for (Long id : selected) {
                        row.add(inr(all.containsKey(id) ? all.get(id).booked : BigDecimal.ZERO));
                    }
                }
            }
            if (wantCod) {
                CodDue cod = codDue(p, sources, creators);
                row.add(inr(cod.amount()));
            }
            rows.add(row);
        }

        List<String> lines = new ArrayList<>();
        String what = wantReceipts && wantCod ? "Received in bank and COD to collect"
                : wantReceipts ? "Received in bank" : "COD to collect on delivery";
        String filters = filterText(q, true, true);
        lines.add(spaceJoin(what, filters) + ", by period:");
        if (wantReceipts) {
            lines.add("Received = money captured when the orders were punched, in "
                    + (selected.isEmpty() ? "all bank / QR accounts" : sentenceList(
                    selected.stream().map(dir::accountName).toList())) + ".");
        }
        if (wantCod) {
            lines.add("COD to collect = cash on delivery due on orders placed in that period (cancelled and rejected"
                    + " orders left out).");
        }
        return new ChatbotSection(lines, new Table(headers, rows));
    }

    private static Table receiptTable(Map<Long, Received> rows, Received total, ChatbotDirectory dir) {
        boolean showVerified = total.verified.signum() != 0;
        boolean showPending = total.pending.signum() != 0;
        boolean showRejected = total.rejected.signum() != 0;
        boolean showNotReviewed = total.notReviewed.signum() != 0;
        List<String> headers = new ArrayList<>(List.of("Account", "Received"));
        if (showVerified) {
            headers.add("Verified");
        }
        if (showPending) {
            headers.add("Awaiting verification");
        }
        if (showRejected) {
            headers.add("Rejected");
        }
        if (showNotReviewed) {
            headers.add("Not reviewed");
        }
        List<List<String>> out = new ArrayList<>();
        for (Map.Entry<Long, Received> e : rows.entrySet()) {
            out.add(receiptRow(dir.accountName(e.getKey()), e.getValue(), showVerified, showPending, showRejected,
                    showNotReviewed));
        }
        if (rows.size() > 1) {
            out.add(receiptRow("Total", total, showVerified, showPending, showRejected, showNotReviewed));
        }
        return new Table(headers, out);
    }

    private static List<String> receiptRow(String label, Received r, boolean verified, boolean pending,
                                           boolean rejected, boolean notReviewed) {
        List<String> cells = new ArrayList<>(List.of(label, inr(r.booked)));
        if (verified) {
            cells.add(inr(r.verified));
        }
        if (pending) {
            cells.add(inr(r.pending));
        }
        if (rejected) {
            cells.add(inr(r.rejected));
        }
        if (notReviewed) {
            cells.add(inr(r.notReviewed));
        }
        return cells;
    }

    private static String splitText(Received r) {
        List<String> parts = new ArrayList<>();
        if (r.verified.signum() != 0) {
            parts.add(inr(r.verified) + " verified");
        }
        if (r.pending.signum() != 0) {
            parts.add(inr(r.pending) + " awaiting verification");
        }
        if (r.rejected.signum() != 0) {
            parts.add(inr(r.rejected) + " rejected by the verifier");
        }
        if (r.notReviewed.signum() != 0) {
            parts.add(inr(r.notReviewed) + " not reviewed (cash, Shopify or older orders)");
        }
        return parts.isEmpty() ? "" : "Of this: " + sentenceList(parts) + ".";
    }

    private static String unknownAccounts(ParsedQuestion q, ChatbotDirectory dir) {
        List<String> quoted = q.unresolvedAccounts().stream().map(w -> "\u201C" + w + "\u201D").toList();
        String known = dir.accounts().isEmpty()
                ? "No bank or QR accounts are set up yet."
                : "The accounts I know are: " + sentenceList(dir.accounts().stream().map(AccountRef::name).toList()) + ".";
        return "I couldn't find an account called " + sentenceList(quoted) + ". " + known;
    }

    private static String spaceJoin(String a, String b) {
        boolean noA = a == null || a.isBlank();
        boolean noB = b == null || b.isBlank();
        if (noA) {
            return noB ? "" : b.trim();
        }
        return noB ? a.trim() : a.trim() + " " + b.trim();
    }

    // ------------------------------------------------------------------ backlog + attention

    /** Money still to collect right now (Reconciliation page's collectible summary). */
    ChatbotSection collectible(ParsedQuestion q) {
        CodAgingResponse aging = codAging.aging();
        CollectibleSummaryResponse c = reconciliation.collectibleSummary(aging);
        List<String> lines = new ArrayList<>();
        lines.add("Money still to collect right now: " + inr(c.totalCollectible()) + ".");
        if (c.codOverSlaCount() > 0) {
            lines.add(inr(c.codOverSlaAmount()) + " of the courier's COD (" + plural(c.codOverSlaCount(), "order")
                    + ") is older than " + c.slaDays() + " days and worth chasing.");
        }
        if (!q.periodDefaulted()) {
            lines.add("This is the current backlog across all dates, so the period you mentioned does not apply.");
        }
        List<List<String>> rows = List.of(
                List.of("Customers still owe on active orders", inr(c.customerOutstanding())),
                List.of("Courier owes us for delivered COD orders", inr(c.codPendingFromCourier())),
                List.of("Loss claims pending (" + count(c.pendingClaims()) + ")", inr(c.pendingClaimsAmount())),
                List.of("Total", inr(c.totalCollectible())));
        return new ChatbotSection(lines, new Table(List.of("What", "Amount"), rows));
    }

    /** The owner's "what needs attention" snapshot (same numbers as the dashboard). */
    ChatbotSection attention() {
        OwnerSnapshotResponse s = ownerSnapshot.snapshot();
        List<String> lines = new ArrayList<>();
        // The snapshot's "revenue today" is the upfront amount received on today's orders (as the dashboard
        // words it), not sales, so it is worded that way here.
        lines.add("Today so far (" + s.date() + "): " + plural(s.ordersToday(), "order") + " placed, "
                + inr(s.revenueToday()) + " received upfront on them."
                + (s.topSalespersonName() != null
                ? " Top salesperson by order value: " + s.topSalespersonName() + " with "
                + inr(s.topSalespersonRevenue()) + "."
                : ""));

        List<List<String>> rows = new ArrayList<>();
        addAttention(rows, "Orders waiting for approval", s.approvalsWaiting(), null);
        addAttention(rows, "Payments waiting for verification", s.paymentsPending(), null);
        addAttention(rows, "Failed deliveries (retry or return)", s.failedDeliveries(), null);
        addAttention(rows, "Returned to origin (RTO)", s.rtoCount(), null);
        if (s.codToCollect() != null && s.codToCollect().signum() != 0) {
            rows.add(List.of("COD pending from courier", "", inr(s.codToCollect())));
        }
        addAttention(rows, "COD older than the courier SLA", s.codOverSla(), s.codOverSlaAmount());
        addAttention(rows, "Loss claims pending", s.pendingClaims(), null);
        addAttention(rows, "Shipments stuck with QuikShip", s.stuckShipments(), null);

        if (rows.isEmpty()) {
            lines.add("Nothing needs your attention right now.");
            return ChatbotSection.ofLines(lines);
        }
        lines.add("Open items (these count all dates, not just today):");
        return new ChatbotSection(lines, new Table(List.of("Open item", "Count", "Amount"), rows));
    }

    private static void addAttention(List<List<String>> rows, String label, long count, BigDecimal amount) {
        if (count > 0) {
            rows.add(List.of(label, count(count), amount == null ? "" : inr(amount)));
        }
    }
}
