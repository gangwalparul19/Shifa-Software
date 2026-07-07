package com.shifa.oms.order;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The Order aggregate root, mapped to the {@code orders} table (design data
 * model). Owns its line items, payment capture, and status history through
 * unidirectional {@code @OneToMany} associations with an {@code order_id} join
 * column, so the whole aggregate is persisted atomically in one transaction
 * (Req 7.11, 8.2).
 *
 * <p>Concurrency is guarded by an optimistic-lock {@link #version} ({@code @Version}).
 * {@code created_at}/{@code updated_at} are filled by DB defaults and therefore
 * are not written on insert/update (following the {@code Product} entity's
 * pattern). Monetary fields are {@code DECIMAL(12,2)} carried as
 * {@link BigDecimal}; the derived values are computed by the domain
 * {@code PaymentCalculator} before an instance is built.
 */
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_code", nullable = false, unique = true, length = 30)
    private String orderCode;

    /**
     * The allocated invoice number (Wave 3, Feature 1), assigned on first invoice
     * generation and reused thereafter so re-downloads are stable. Null until the
     * first invoice is produced.
     */
    @Column(name = "invoice_number", length = 60)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private OrderSource source;

    @Column(name = "created_by")
    private Long createdBy;

    /**
     * The registered customer who placed this order while logged in on the
     * storefront (Phase B: customer accounts). Null for salesperson orders and
     * anonymous/guest checkout. Drives account order history alongside the
     * customer mobile match.
     */
    @Column(name = "customer_user_id")
    private Long customerUserId;

    @Column(name = "customer_name", nullable = false, length = 100)
    private String customerName;

    @Column(name = "customer_mobile", nullable = false, length = 10)
    private String customerMobile;

    @Column(name = "address_line", nullable = false, length = 250)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 100)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 6)
    private String postalCode;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "amount_received", nullable = false, precision = 12, scale = 2)
    private BigDecimal amountReceived = BigDecimal.ZERO;

    @Column(name = "remaining_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal remainingAmount = BigDecimal.ZERO;

    @Column(name = "cod_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal codAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false, length = 30)
    private OrderStatus orderStatus;

    @Column(name = "customer_outstanding", nullable = false, precision = 12, scale = 2)
    private BigDecimal customerOutstanding = BigDecimal.ZERO;

    /**
     * The coupon code applied to this order (upper-cased snapshot), or null when
     * no coupon was used (Phase D). Stored as a snapshot rather than an FK so a
     * historical order survives coupon deletion/rename.
     */
    @Column(name = "coupon_code", length = 40)
    private String couponCode;

    /**
     * The money discount granted by the applied coupon (Phase D), 0.00 when none.
     * {@link #totalAmount} stores the NET payable (subtotal − discount) so COD /
     * remaining already reflect the discount.
     */
    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "payment_screenshot_key", length = 512)
    private String paymentScreenshotKey;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id ASC")
    private List<OrderLineItem> lineItems = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id ASC")
    private List<OrderPayment> payments = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("id ASC")
    private List<OrderStatusHistory> statusHistory = new ArrayList<>();

    protected OrderEntity() {
        // Required by JPA.
    }

    public OrderEntity(String orderCode, OrderSource source, Long createdBy,
                       String customerName, String customerMobile, String addressLine,
                       String city, String state, String postalCode) {
        this.orderCode = orderCode;
        this.source = source;
        this.createdBy = createdBy;
        this.customerName = customerName;
        this.customerMobile = customerMobile;
        this.addressLine = addressLine;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
    }

    /** Adds a line item to the aggregate. */
    public void addLineItem(OrderLineItem item) {
        this.lineItems.add(item);
    }

    /** Adds a payment capture row to the aggregate. */
    public void addPayment(OrderPayment payment) {
        this.payments.add(payment);
    }

    /** Appends a status-history row to the aggregate. */
    public void addStatusHistory(OrderStatusHistory entry) {
        this.statusHistory.add(entry);
    }

    /** Applies the derived payment amounts and classification (Req 7.4, 7.5, 7.7-7.9). */
    public void applyAmounts(BigDecimal total, BigDecimal received, BigDecimal remaining,
                             BigDecimal cod, PaymentStatus status) {
        this.totalAmount = total;
        this.amountReceived = received;
        this.remainingAmount = remaining;
        this.codAmount = cod;
        this.paymentStatus = status;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    /** Records the coupon code + money discount applied at checkout (Phase D). */
    public void applyDiscount(String couponCode, BigDecimal discountAmount) {
        this.couponCode = couponCode;
        this.discountAmount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
    }

    public void setCustomerOutstanding(BigDecimal customerOutstanding) {
        this.customerOutstanding = customerOutstanding;
    }

    public void setPaymentScreenshotKey(String paymentScreenshotKey) {
        this.paymentScreenshotKey = paymentScreenshotKey;
    }

    /** Stores the admin's rejection reason when an order is rejected (Req 9.4). */
    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public Long getId() {
        return id;
    }

    public String getOrderCode() {
        return orderCode;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public OrderSource getSource() {
        return source;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Long getCustomerUserId() {
        return customerUserId;
    }

    public void setCustomerUserId(Long customerUserId) {
        this.customerUserId = customerUserId;
    }

    public String getCustomerName() {
        return customerName;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    public String getAddressLine() {
        return addressLine;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getAmountReceived() {
        return amountReceived;
    }

    public BigDecimal getRemainingAmount() {
        return remainingAmount;
    }

    public BigDecimal getCodAmount() {
        return codAmount;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public BigDecimal getCustomerOutstanding() {
        return customerOutstanding;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public String getCouponCode() {
        return couponCode;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public String getPaymentScreenshotKey() {
        return paymentScreenshotKey;
    }

    public Long getVersion() {
        return version;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public List<OrderLineItem> getLineItems() {
        return lineItems;
    }

    public List<OrderPayment> getPayments() {
        return payments;
    }

    public List<OrderStatusHistory> getStatusHistory() {
        return statusHistory;
    }
}
