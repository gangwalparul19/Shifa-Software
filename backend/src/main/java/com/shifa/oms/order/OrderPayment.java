package com.shifa.oms.order;

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
 * One order payment transaction, mapped to {@code payments}. Legacy rows may
 * have only amount/screenshot; new rows also preserve method, receiving-account
 * snapshot, and UTR for reconciliation.
 */
@Entity
@Table(name = "payments")
public class OrderPayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "amount_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountReceived = BigDecimal.ZERO;

    @Column(name = "screenshot_key", length = 512)
    private String screenshotKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 30)
    private PaymentMethod paymentMethod;

    @Column(name = "bank_account_id")
    private Long bankAccountId;

    @Column(name = "bank_account_name_snapshot", length = 100)
    private String bankAccountNameSnapshot;

    @Column(name = "bank_account_masked_snapshot", length = 80)
    private String bankAccountMaskedSnapshot;

    @Column(name = "utr", length = 80)
    private String utr;

    /** Canonical UTR used by the database uniqueness boundary and matching queries. */
    @Column(name = "utr_normalized", length = 80)
    private String utrNormalized;

    @Column(name = "captured_at", insertable = false, updatable = false)
    private LocalDateTime capturedAt;

    protected OrderPayment() {
        // Required by JPA.
    }

    public OrderPayment(BigDecimal amountReceived, String screenshotKey) {
        this(amountReceived, screenshotKey, null, null, null, null, null);
    }

    public OrderPayment(BigDecimal amountReceived, String screenshotKey,
                        PaymentMethod paymentMethod, Long bankAccountId,
                        String bankAccountNameSnapshot, String bankAccountMaskedSnapshot,
                        String utr) {
        this.amountReceived = amountReceived;
        this.screenshotKey = screenshotKey;
        this.paymentMethod = paymentMethod;
        this.bankAccountId = bankAccountId;
        this.bankAccountNameSnapshot = bankAccountNameSnapshot;
        this.bankAccountMaskedSnapshot = bankAccountMaskedSnapshot;
        this.utr = UtrNormalizer.normalize(utr);
        this.utrNormalized = this.utr;
    }

    public Long getId() { return id; }
    public BigDecimal getAmountReceived() { return amountReceived; }
    public String getScreenshotKey() { return screenshotKey; }
    public PaymentMethod getPaymentMethod() { return paymentMethod; }
    public Long getBankAccountId() { return bankAccountId; }
    public String getBankAccountNameSnapshot() { return bankAccountNameSnapshot; }
    public String getBankAccountMaskedSnapshot() { return bankAccountMaskedSnapshot; }
    public String getUtr() { return utr; }
    public String getUtrNormalized() { return utrNormalized; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
}
