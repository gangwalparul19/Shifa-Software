package com.shifa.oms.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable before/after record for an administrator payment correction.
 *
 * <p>The live payment snapshot remains on {@link OrderPayment}; this row keeps
 * the values that were replaced and is also the source document for the
 * optional ledger delta voucher.
 */
@Entity
@Table(name = "payment_corrections")
public class PaymentCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "previous_amount_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal previousAmountReceived;

    @Column(name = "corrected_amount_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal correctedAmountReceived;

    @Column(name = "amount_delta", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountDelta;

    @Column(name = "previous_payment_method", length = 30)
    private String previousPaymentMethod;

    @Column(name = "corrected_payment_method", length = 30)
    private String correctedPaymentMethod;

    @Column(name = "previous_bank_account_id")
    private Long previousBankAccountId;

    @Column(name = "corrected_bank_account_id")
    private Long correctedBankAccountId;

    @Column(name = "previous_bank_account_name_snapshot", length = 100)
    private String previousBankAccountNameSnapshot;

    @Column(name = "corrected_bank_account_name_snapshot", length = 100)
    private String correctedBankAccountNameSnapshot;

    @Column(name = "previous_bank_account_masked_snapshot", length = 80)
    private String previousBankAccountMaskedSnapshot;

    @Column(name = "corrected_bank_account_masked_snapshot", length = 80)
    private String correctedBankAccountMaskedSnapshot;

    @Column(name = "previous_utr", length = 80)
    private String previousUtr;

    @Column(name = "corrected_utr", length = 80)
    private String correctedUtr;

    @Column(name = "reason", nullable = false, length = 500)
    private String reason;

    @Column(name = "changed_by")
    private Long changedBy;

    @Column(name = "changed_by_username", length = 100)
    private String changedByUsername;

    @Column(name = "changed_at", insertable = false, updatable = false)
    private LocalDateTime changedAt;

    protected PaymentCorrection() {
        // Required by JPA.
    }

    public PaymentCorrection(Long orderId,
                             BigDecimal previousAmountReceived,
                             BigDecimal correctedAmountReceived,
                             String previousPaymentMethod,
                             String correctedPaymentMethod,
                             Long previousBankAccountId,
                             Long correctedBankAccountId,
                             String previousBankAccountNameSnapshot,
                             String correctedBankAccountNameSnapshot,
                             String previousBankAccountMaskedSnapshot,
                             String correctedBankAccountMaskedSnapshot,
                             String previousUtr,
                             String correctedUtr,
                             String reason,
                             Long changedBy,
                             String changedByUsername) {
        this.orderId = orderId;
        this.previousAmountReceived = previousAmountReceived;
        this.correctedAmountReceived = correctedAmountReceived;
        this.amountDelta = correctedAmountReceived.subtract(previousAmountReceived);
        this.previousPaymentMethod = previousPaymentMethod;
        this.correctedPaymentMethod = correctedPaymentMethod;
        this.previousBankAccountId = previousBankAccountId;
        this.correctedBankAccountId = correctedBankAccountId;
        this.previousBankAccountNameSnapshot = previousBankAccountNameSnapshot;
        this.correctedBankAccountNameSnapshot = correctedBankAccountNameSnapshot;
        this.previousBankAccountMaskedSnapshot = previousBankAccountMaskedSnapshot;
        this.correctedBankAccountMaskedSnapshot = correctedBankAccountMaskedSnapshot;
        this.previousUtr = previousUtr;
        this.correctedUtr = correctedUtr;
        this.reason = reason;
        this.changedBy = changedBy;
        this.changedByUsername = changedByUsername;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public BigDecimal getPreviousAmountReceived() { return previousAmountReceived; }
    public BigDecimal getCorrectedAmountReceived() { return correctedAmountReceived; }
    public BigDecimal getAmountDelta() { return amountDelta; }
    public String getPreviousPaymentMethod() { return previousPaymentMethod; }
    public String getCorrectedPaymentMethod() { return correctedPaymentMethod; }
    public Long getPreviousBankAccountId() { return previousBankAccountId; }
    public Long getCorrectedBankAccountId() { return correctedBankAccountId; }
    public String getPreviousBankAccountNameSnapshot() { return previousBankAccountNameSnapshot; }
    public String getCorrectedBankAccountNameSnapshot() { return correctedBankAccountNameSnapshot; }
    public String getPreviousBankAccountMaskedSnapshot() { return previousBankAccountMaskedSnapshot; }
    public String getCorrectedBankAccountMaskedSnapshot() { return correctedBankAccountMaskedSnapshot; }
    public String getPreviousUtr() { return previousUtr; }
    public String getCorrectedUtr() { return correctedUtr; }
    public String getReason() { return reason; }
    public Long getChangedBy() { return changedBy; }
    public String getChangedByUsername() { return changedByUsername; }
    public LocalDateTime getChangedAt() { return changedAt; }
}
