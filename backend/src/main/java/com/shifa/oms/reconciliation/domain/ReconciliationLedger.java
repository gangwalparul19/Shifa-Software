package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.domain.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The in-memory receivables ledger and the aggregation/settlement operations
 * behind the reconciliation dashboard (Requirement 18.1&ndash;18.6).
 *
 * <p>It holds the {@link Receivable} rows produced by settlement
 * (task&nbsp;5.1) and derives, per courier company:
 * <ul>
 *   <li>the total unsettled {@code COD_RECEIVABLE} (Req 18.1),</li>
 *   <li>the total unsettled {@code CLAIM_RECEIVABLE} (Req 18.2),</li>
 *   <li>the list of unsettled COD receivables (Req 18.3),</li>
 *   <li>the courier's total outstanding receivable (COD + claim).</li>
 * </ul>
 * Because RTO orders never produce a receivable (see
 * {@link SettlementProcessor#onReturnToOrigin}), they are inherently excluded
 * from COD totals (Req 18.6).
 *
 * <p>{@link #settle(long, LocalDate)} records the settlement date and, because
 * outstanding totals are derived from the set of <em>unsettled</em> receivables,
 * settling a receivable reduces the courier's outstanding by exactly that
 * receivable's amount and is idempotent (Req 18.5).
 *
 * <p>This class is pure domain state with no persistence; the persistence and
 * REST layers (task&nbsp;16) wrap it.
 */
public class ReconciliationLedger {

    private final Map<Long, Receivable> receivables = new LinkedHashMap<>();

    /**
     * Records a receivable in the ledger.
     *
     * @param receivable the receivable to add (never {@code null})
     * @throws ValidationException if a receivable with the same id already exists
     */
    public void record(Receivable receivable) {
        Objects.requireNonNull(receivable, "receivable");
        if (receivables.containsKey(receivable.id())) {
            throw new ValidationException("Duplicate receivable id: " + receivable.id());
        }
        receivables.put(receivable.id(), receivable);
    }

    /** All receivables in insertion order (unmodifiable). */
    public List<Receivable> all() {
        return List.copyOf(receivables.values());
    }

    /**
     * The per-courier total of unsettled COD receivables from delivered COD
     * orders (Requirement 18.1). RTO orders contribute nothing because they never
     * create a receivable (Req 18.6).
     */
    public Money codReceivableTotal(long courierCompanyId) {
        return sumUnsettled(courierCompanyId, ReceivableType.COD_RECEIVABLE);
    }

    /**
     * The per-courier total of unsettled claim receivables from lost/damaged
     * shipments (Requirement 18.2).
     */
    public Money claimReceivableTotal(long courierCompanyId) {
        return sumUnsettled(courierCompanyId, ReceivableType.CLAIM_RECEIVABLE);
    }

    /**
     * The total outstanding receivable for a courier: the sum of all its
     * unsettled receivables (COD + claim). Settling a receivable reduces this by
     * exactly that receivable's amount (Requirement 18.5).
     */
    public Money outstandingForCourier(long courierCompanyId) {
        Money total = Money.ZERO;
        for (Receivable r : receivables.values()) {
            if (r.courierCompanyId() == courierCompanyId && !r.isSettled()) {
                total = total.add(r.amount());
            }
        }
        return total;
    }

    /**
     * The list of unsettled COD receivables (delivered COD orders whose
     * receivable has not yet been marked settled), in insertion order
     * (Requirement 18.3).
     */
    public List<Receivable> unsettledCodReceivables() {
        List<Receivable> result = new ArrayList<>();
        for (Receivable r : receivables.values()) {
            if (r.type() == ReceivableType.COD_RECEIVABLE && !r.isSettled()) {
                result.add(r);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Marks the identified receivable settled on the given date and reduces the
     * courier's outstanding receivable accordingly (Requirement 18.5). Idempotent:
     * settling an already-settled receivable does not change anything and returns
     * {@code false}.
     *
     * @param receivableId the receivable to settle
     * @param date         the settlement date (never {@code null})
     * @return {@code true} if this call settled a previously-unsettled receivable;
     *         {@code false} if it was already settled
     * @throws ValidationException if no receivable with the id exists
     */
    public boolean settle(long receivableId, LocalDate date) {
        Receivable receivable = receivables.get(receivableId);
        if (receivable == null) {
            throw new ValidationException("No receivable with id: " + receivableId);
        }
        return receivable.settle(date);
    }

    private Money sumUnsettled(long courierCompanyId, ReceivableType type) {
        Money total = Money.ZERO;
        for (Receivable r : receivables.values()) {
            if (r.courierCompanyId() == courierCompanyId && r.type() == type && !r.isSettled()) {
                total = total.add(r.amount());
            }
        }
        return total;
    }

    /**
     * Segregates a set of orders into prepaid and COD categories for the
     * reconciliation views (Requirement 18.4). Every order appears in exactly one
     * group: fully-paid orders are prepaid, all others (COD and partially paid)
     * are COD.
     *
     * @param orders the orders to segregate (never {@code null})
     * @return the disjoint, exhaustive prepaid/COD grouping
     */
    public static Segregation segregate(Collection<OrderSettlementView> orders) {
        Objects.requireNonNull(orders, "orders");
        List<OrderSettlementView> prepaid = new ArrayList<>();
        List<OrderSettlementView> cod = new ArrayList<>();
        for (OrderSettlementView order : orders) {
            if (order.isPrepaid()) {
                prepaid.add(order);
            } else {
                cod.add(order);
            }
        }
        return new Segregation(List.copyOf(prepaid), List.copyOf(cod));
    }

    /**
     * A disjoint, exhaustive partition of orders into prepaid and COD groups
     * (Requirement 18.4).
     *
     * @param prepaid fully-paid orders
     * @param cod     COD and partially-paid orders
     */
    public record Segregation(List<OrderSettlementView> prepaid, List<OrderSettlementView> cod) {

        public Segregation {
            Objects.requireNonNull(prepaid, "prepaid");
            Objects.requireNonNull(cod, "cod");
        }
    }
}
