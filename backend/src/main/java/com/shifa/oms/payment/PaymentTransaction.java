package com.shifa.oms.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * A single online-payment attempt for an order, mapped to the
 * {@code payment_transactions} table (V7 migration). A row is created at
 * {@code initiate} in {@link PaymentTransactionStatus#CREATED} and moves to
 * {@code PAID} or {@code FAILED} at {@code confirm}.
 *
 * <p>{@code created_at}/{@code updated_at} are managed by DB defaults (following
 * the aggregate pattern used elsewhere), so they are not written from Java.
 */
@Entity
@Table(name = "payment_transactions")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "gateway", nullable = false, length = 32)
    private String gateway;

    @Column(name = "gateway_order_id", nullable = false, length = 128)
    private String gatewayOrderId;

    @Column(name = "gateway_payment_id", length = 128)
    private String gatewayPaymentId;

    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal amount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PaymentTransactionStatus status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    protected PaymentTransaction() {
        // Required by JPA.
    }

    public PaymentTransaction(Long orderId, String gateway, String gatewayOrderId, BigDecimal amount) {
        this.orderId = orderId;
        this.gateway = gateway;
        this.gatewayOrderId = gatewayOrderId;
        this.amount = amount;
        this.status = PaymentTransactionStatus.CREATED;
    }

    /** Records a successful, verified payment (idempotent). */
    public void markPaid(String gatewayPaymentId) {
        this.gatewayPaymentId = gatewayPaymentId;
        this.status = PaymentTransactionStatus.PAID;
    }

    /** Records a failed verification attempt. */
    public void markFailed(String gatewayPaymentId) {
        this.gatewayPaymentId = gatewayPaymentId;
        this.status = PaymentTransactionStatus.FAILED;
    }

    public boolean isPaid() {
        return status == PaymentTransactionStatus.PAID;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getGateway() {
        return gateway;
    }

    public String getGatewayOrderId() {
        return gatewayOrderId;
    }

    public String getGatewayPaymentId() {
        return gatewayPaymentId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentTransactionStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
