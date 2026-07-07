package com.shifa.oms.reconciliation;

import com.shifa.oms.reconciliation.domain.ReceivableType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A persisted receivable, mapped to the {@code receivables} table (design data
 * model; Req 16.2, 17.2, 18.*). This is the JPA counterpart of the pure domain
 * {@link com.shifa.oms.reconciliation.domain.Receivable}: the settlement side
 * effects wired into courier status changes (task 14) create a row here so the
 * reconciliation dashboard (task 16) can aggregate and settle them.
 *
 * <p>A COD receivable is created when a COD order is delivered (Req 16.2); a
 * claim receivable is created when an order is lost/damaged (Req 17.2). Rows are
 * created unsettled ({@code settled=false}); marking settled records the
 * settlement date (Req 18.5).
 */
@Entity
@Table(name = "receivables")
public class ReceivableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "courier_company_id")
    private Long courierCompanyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private ReceivableType type;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "settled", nullable = false)
    private boolean settled = false;

    @Column(name = "settled_date")
    private LocalDate settledDate;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected ReceivableEntity() {
        // Required by JPA.
    }

    public ReceivableEntity(Long orderId, Long courierCompanyId, ReceivableType type, BigDecimal amount) {
        this.orderId = orderId;
        this.courierCompanyId = courierCompanyId;
        this.type = type;
        this.amount = amount;
    }

    /**
     * Marks this receivable settled on the given date, once (Req 18.5).
     *
     * @param date the settlement date
     * @return {@code true} if this call settled a previously-unsettled row
     */
    public boolean settle(LocalDate date) {
        if (settled) {
            return false;
        }
        this.settled = true;
        this.settledDate = date;
        return true;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getCourierCompanyId() {
        return courierCompanyId;
    }

    public ReceivableType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isSettled() {
        return settled;
    }

    public LocalDate getSettledDate() {
        return settledDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
