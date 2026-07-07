package com.shifa.oms.reconciliation.domain;

import com.shifa.oms.common.ValidationException;
import com.shifa.oms.order.domain.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A single amount owed to the business by a courier company — either cash
 * collected on a COD delivery or a claim for a lost/damaged shipment
 * (Requirement 16.2, 17.2; design table {@code receivables}).
 *
 * <p>A receivable is created unsettled. Marking it settled records the
 * settlement date exactly once ({@link #settle(LocalDate)}); a second settle is
 * a no-op so that reconciliation operations are idempotent (Requirement 18.5).
 *
 * <p>This is pure, persistence-free domain state reused by the
 * {@link ReconciliationLedger} and produced by the {@link SettlementProcessor}.
 */
public final class Receivable {

    private final long id;
    private final long orderId;
    private final long courierCompanyId;
    private final ReceivableType type;
    private final Money amount;

    private boolean settled;
    private LocalDate settledDate;

    /**
     * Creates a new, unsettled receivable.
     *
     * @param id               unique receivable identifier
     * @param orderId          the order this receivable stems from
     * @param courierCompanyId the courier company that owes the amount
     * @param type             COD or claim (never {@code null})
     * @param amount           the amount owed (never {@code null}, not negative)
     */
    public Receivable(long id, long orderId, long courierCompanyId, ReceivableType type, Money amount) {
        this.id = id;
        this.orderId = orderId;
        this.courierCompanyId = courierCompanyId;
        this.type = Objects.requireNonNull(type, "type");
        this.amount = Objects.requireNonNull(amount, "amount");
        if (amount.isNegative()) {
            throw new ValidationException("Receivable amount must not be negative");
        }
    }

    public long id() {
        return id;
    }

    public long orderId() {
        return orderId;
    }

    public long courierCompanyId() {
        return courierCompanyId;
    }

    public ReceivableType type() {
        return type;
    }

    /** The amount owed (never changes once recorded). */
    public Money amount() {
        return amount;
    }

    /** Whether this receivable has been marked settled. */
    public boolean isSettled() {
        return settled;
    }

    /** The date this receivable was settled, or {@code null} if still outstanding. */
    public LocalDate settledDate() {
        return settledDate;
    }

    /**
     * Marks this receivable settled on the given date, recording the settlement
     * date (Requirement 18.5). Idempotent: if the receivable is already settled,
     * the settlement date is retained and this method reports that no change
     * occurred.
     *
     * @param date the settlement date (never {@code null})
     * @return {@code true} if this call settled a previously-unsettled
     *         receivable; {@code false} if it was already settled
     */
    public boolean settle(LocalDate date) {
        Objects.requireNonNull(date, "date");
        if (settled) {
            return false;
        }
        this.settled = true;
        this.settledDate = date;
        return true;
    }
}
