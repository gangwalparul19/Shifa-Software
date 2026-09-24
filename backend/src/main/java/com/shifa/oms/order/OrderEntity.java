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

    /**
     * How this order is fulfilled for last-mile delivery — QuikShipX (default) or
     * Shifa's own in-house team. Distinct from {@link #source}. Mapped to
     * {@code orders.delivery_method} (V60).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_method", nullable = false, length = 20)
    private DeliveryMethod deliveryMethod = DeliveryMethod.QUIKSHIPX;

    /**
     * The origin channel of the lead (Req 4.1-4.4), distinct from {@link #source}.
     * Nullable at the column level so seeded/pre-existing rows stay valid and
     * report as {@code UNSPECIFIED}; required at the service/DTO layer for new
     * salesperson orders. Mapped to {@code orders.lead_source} (V23).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "lead_source", length = 20)
    private LeadSource leadSource;

    /** Optional free-text note, meaningful only when {@link #leadSource} is {@code OTHER} (Req 4.5). */
    @Column(name = "lead_source_note", length = 200)
    private String leadSourceNote;

    /**
     * Optional free-text order note captured by the salesperson at order entry
     * (e.g. a specific customer ask). Nullable; mapped to {@code orders.notes} (V29).
     */
    @Column(name = "notes", length = 1000)
    private String notes;

    /**
     * Customer email for milestone emails (Req 7.2, 10.7, 11.4). When absent the
     * email channel is skipped and the skip recorded. Mapped to
     * {@code orders.customer_email} (V23).
     */
    @Column(name = "customer_email", length = 150)
    private String customerEmail;

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

    /**
     * Optional alternate contact number for the customer (product-audit §4.5),
     * used for failed-delivery follow-up. Nullable; mapped to
     * {@code orders.alternate_mobile} (V39).
     */
    @Column(name = "alternate_mobile", length = 10)
    private String alternateMobile;

    /**
     * Optional buyer GSTIN captured at order entry when the customer is a
     * GST-registered person (gst-filing-compliance Req 1.1). Nullable; drives
     * GSTR-1 B2B / B2CL / B2CS supply classification. Mapped to
     * {@code orders.buyer_gstin} (V52).
     */
    @Column(name = "buyer_gstin", length = 15)
    private String buyerGstin;

    @Column(name = "address_line", nullable = false, length = 250)
    private String addressLine;

    @Column(name = "city", nullable = false, length = 100)
    private String city;

    @Column(name = "state", nullable = false, length = 100)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 6)
    private String postalCode;

    /**
     * Destination country for an international order (India/Outside India order
     * entry, V67). NULL for a domestic (India) order — the default. An
     * international order stores the free-text address in {@link #addressLine} and
     * leaves {@link #city}/{@link #state}/{@link #postalCode} empty.
     */
    @Column(name = "country", length = 60)
    private String country;

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

    /**
     * The order-level discount kind entered at order entry — "FLAT" or "PERCENT"
     * — or null when none (product-catalog-pricing-gst Req 6.1, 6.4). The raw
     * entered value is {@link #discountValue}; the resolved rupee reduction is
     * {@link #discountAmount}.
     */
    @Column(name = "discount_type", length = 10)
    private String discountType;

    /** The raw discount value as entered (a rupee amount for FLAT, a percent for PERCENT); null when none. */
    @Column(name = "discount_value", precision = 12, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    /**
     * The categorized reason an order was rejected (rejection-status feature):
     * Rate Issue / Address-Pincode Issue (admin) or Payment Issue (payment panel).
     * Null for orders never rejected. Mapped to {@code orders.reject_reason} (V66);
     * {@link #rejectionReason} above is the accompanying free-text note.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", length = 30)
    private RejectReason rejectReason;

    /**
     * The categorized reason this order was marked RTO when done manually by a
     * packer/admin scanning the label (label redesign feature); null for orders
     * that were never RTO'd, or RTO'd automatically via the courier webhook/poll
     * path (which does not set a reason). Mapped to {@code orders.rto_reason} (V61).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "rto_reason", length = 30)
    private RtoReason rtoReason;

    /** Optional free-text note accompanying {@link #rtoReason} (mainly for {@code OTHER}). */
    @Column(name = "rto_reason_note", length = 500)
    private String rtoReasonNote;

    /**
     * Who the packed order was handed to at the handover step (courier person /
     * agency name) and an optional phone (product-audit §4.3). Nullable; mapped
     * to {@code orders.handover_name}/{@code handover_phone} (V40).
     */
    @Column(name = "handover_name", length = 120)
    private String handoverName;

    @Column(name = "handover_phone", length = 10)
    private String handoverPhone;

    /**
     * Optional vehicle / transport reference for the leg Shifa's own team
     * arranges on an in-house delivery — a bus operator's vehicle number, a
     * train number, a taxi registration, our own van, and so on (in-house
     * delivery feature). An in-house order has no courier AWB, so this plus
     * {@link #handoverName}/{@link #handoverPhone} is what identifies the
     * shipment in the real world. Nullable; mapped to
     * {@code orders.vehicle_number} (V63).
     */
    @Column(name = "vehicle_number", length = 40)
    private String vehicleNumber;

    /**
     * Number of physical boxes/packages the order ships in (product-audit §4.2);
     * drives how many label copies are printed. Defaults to 1. Mapped to
     * {@code orders.package_count} (V41).
     */
    @Column(name = "package_count", nullable = false)
    private int packageCount = 1;

    /**
     * Payment authenticity verification (product-audit §4.4) — an additive layer
     * that does not gate the order status. Non-null only for prepaid / partially-
     * paid orders. Mapped to {@code orders.payment_verification_*} (V42).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_verification_status", length = 20)
    private PaymentVerificationStatus paymentVerificationStatus;

    @Column(name = "payment_verified_by")
    private Long paymentVerifiedBy;

    @Column(name = "payment_verified_at")
    private LocalDateTime paymentVerifiedAt;

    @Column(name = "payment_verification_note", length = 500)
    private String paymentVerificationNote;

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

    /**
     * Every payment proof attached to this order, in upload order (V65). Index 0
     * is the primary proof and is mirrored onto the legacy single-valued
     * {@link #paymentScreenshotKey} so existing read paths stay correct.
     */
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    @OrderBy("sortOrder ASC, id ASC")
    private List<OrderPaymentScreenshot> paymentScreenshots = new ArrayList<>();

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

    /**
     * Replaces every line item with the given list (admin edit-order feature):
     * clears the existing lines — {@code orphanRemoval = true} on the mapping
     * deletes the old rows on flush — then adds the new ones, exactly like
     * removing then re-adding via {@link #addLineItem}.
     */
    public void replaceLineItems(List<OrderLineItem> newLines) {
        this.lineItems.clear();
        this.lineItems.addAll(newLines);
    }

    /** Adds a payment capture row to the aggregate. */
    public void addPayment(OrderPayment payment) {
        this.payments.add(payment);
    }

    /** Appends a status-history row to the aggregate. */
    public void addStatusHistory(OrderStatusHistory entry) {
        this.statusHistory.add(entry);
    }

    /**
     * Attaches a payment proof to the aggregate (V65), assigning it the next
     * position in the upload order. The FIRST proof attached is also mirrored
     * onto the legacy {@link #paymentScreenshotKey} column, so the
     * screenshot-required rule and every {@code paymentScreenshotAvailable}
     * projection keep working with no change.
     */
    public void addPaymentScreenshot(String storageKey, String filename,
                                     String contentType, Long byteSize) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        int next = this.paymentScreenshots.size();
        this.paymentScreenshots.add(
                new OrderPaymentScreenshot(storageKey, filename, contentType, byteSize, next));
        if (next == 0) {
            this.paymentScreenshotKey = storageKey;
        }
    }

    /** Every payment proof attached to this order, in upload order (V65). */
    public List<OrderPaymentScreenshot> getPaymentScreenshots() {
        return paymentScreenshots;
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

    /**
     * Records an order-level discount entered at order entry: its type
     * ("FLAT"/"PERCENT"), the raw entered value, and the resolved rupee reduction
     * (product-catalog-pricing-gst Req 6). {@link #totalAmount} stores the NET
     * payable, so COD / remaining already reflect the discount.
     */
    public void applyOrderDiscount(String discountType, BigDecimal discountValue, BigDecimal discountAmount) {
        this.discountType = discountType;
        this.discountValue = discountValue;
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

    public RejectReason getRejectReason() {
        return rejectReason;
    }

    /**
     * Records why this order was rejected (rejection-status feature): a category
     * (Rate / Address-Pincode / Payment / Other) plus an optional free-text note.
     */
    public void setRejectReason(RejectReason rejectReason, String note) {
        this.rejectReason = rejectReason;
        this.rejectionReason = note;
    }

    public RtoReason getRtoReason() {
        return rtoReason;
    }

    public String getRtoReasonNote() {
        return rtoReasonNote;
    }

    /** Records why this order was manually marked RTO (label redesign feature). */
    public void setRtoReason(RtoReason rtoReason, String rtoReasonNote) {
        this.rtoReason = rtoReason;
        this.rtoReasonNote = rtoReasonNote;
    }

    public String getHandoverName() {
        return handoverName;
    }

    public String getHandoverPhone() {
        return handoverPhone;
    }

    /** Records who a packed order was handed to at the handover step (V40, product-audit §4.3). */
    public void setHandoverDetails(String handoverName, String handoverPhone) {
        this.handoverName = handoverName;
        this.handoverPhone = handoverPhone;
    }

    /** The optional in-house vehicle / transport reference (V63), or {@code null}. */
    public String getVehicleNumber() {
        return vehicleNumber;
    }

    /**
     * Records the optional in-house vehicle / transport reference — bus vehicle
     * number, train number, taxi registration, own van (V63, in-house delivery
     * feature). A {@code null} clears it.
     */
    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public int getPackageCount() {
        return packageCount < 1 ? 1 : packageCount;
    }

    /** Sets the number of physical boxes the order ships in (V41, product-audit §4.2). */
    public void setPackageCount(int packageCount) {
        this.packageCount = packageCount < 1 ? 1 : packageCount;
    }

    public PaymentVerificationStatus getPaymentVerificationStatus() {
        return paymentVerificationStatus;
    }

    public Long getPaymentVerifiedBy() {
        return paymentVerifiedBy;
    }

    public LocalDateTime getPaymentVerifiedAt() {
        return paymentVerifiedAt;
    }

    public String getPaymentVerificationNote() {
        return paymentVerificationNote;
    }

    /** Marks this order's payment as awaiting verification (prepaid/partially-paid orders only). */
    public void markPaymentPendingVerification() {
        this.paymentVerificationStatus = PaymentVerificationStatus.PENDING;
    }

    /** Records a verifier's decision on the payment's authenticity (product-audit §4.4). */
    public void recordPaymentVerification(PaymentVerificationStatus status, Long verifiedBy,
                                          LocalDateTime verifiedAt, String note) {
        this.paymentVerificationStatus = status;
        this.paymentVerifiedBy = verifiedBy;
        this.paymentVerifiedAt = verifiedAt;
        this.paymentVerificationNote = note;
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

    public DeliveryMethod getDeliveryMethod() {
        return deliveryMethod == null ? DeliveryMethod.QUIKSHIPX : deliveryMethod;
    }

    /** Sets the delivery method (defaults to QUIKSHIPX when null, e.g. legacy rows). */
    public void setDeliveryMethod(DeliveryMethod deliveryMethod) {
        this.deliveryMethod = deliveryMethod == null ? DeliveryMethod.QUIKSHIPX : deliveryMethod;
    }

    /** Whether this order is flagged for in-house (non-QuikShipX) delivery. */
    public boolean isInHouseDelivery() {
        return getDeliveryMethod() == DeliveryMethod.IN_HOUSE;
    }

    public LeadSource getLeadSource() {
        return leadSource;
    }

    /** Records the lead's origin channel and, for {@code OTHER}, an optional note (Req 4.1, 4.5). */
    public void setLeadSource(LeadSource leadSource) {
        this.leadSource = leadSource;
    }

    public String getLeadSourceNote() {
        return leadSourceNote;
    }

    public void setLeadSourceNote(String leadSourceNote) {
        this.leadSourceNote = leadSourceNote;
    }

    public String getNotes() {
        return notes;
    }

    /** Records the salesperson's free-text order note captured at order entry (V29). */
    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getCustomerEmail() {
        return customerEmail;
    }

    public void setCustomerEmail(String customerEmail) {
        this.customerEmail = customerEmail;
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

    /** Corrects the customer name (admin edit-order feature — fixes salesperson entry errors). */
    public void setCustomerName(String customerName) {
        this.customerName = customerName;
    }

    public String getCustomerMobile() {
        return customerMobile;
    }

    /** Corrects the customer mobile number (admin edit-order feature). */
    public void setCustomerMobile(String customerMobile) {
        this.customerMobile = customerMobile;
    }

    public String getAlternateMobile() {
        return alternateMobile;
    }

    /** Records the optional alternate contact number captured at order entry (V39). */
    public void setAlternateMobile(String alternateMobile) {
        this.alternateMobile = alternateMobile;
    }

    public String getBuyerGstin() {
        return buyerGstin;
    }

    /** Records the optional buyer GSTIN captured at order entry (V52). */
    public void setBuyerGstin(String buyerGstin) {
        this.buyerGstin = buyerGstin;
    }

    public String getAddressLine() {
        return addressLine;
    }

    /** Corrects the shipping address line (admin edit-order feature). */
    public void setAddressLine(String addressLine) {
        this.addressLine = addressLine;
    }

    public String getCity() {
        return city;
    }

    /** Corrects the shipping city (admin edit-order feature). */
    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    /** Corrects the shipping state (admin edit-order feature). */
    public void setState(String state) {
        this.state = state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    /** Corrects the shipping postal code (admin edit-order feature). */
    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCountry() {
        return country;
    }

    /** Records the destination country for an international order (V67); null = India. */
    public void setCountry(String country) {
        this.country = country;
    }

    /**
     * Whether this is an international (outside-India) order — a non-blank country
     * other than India. Drives GST export treatment (taxed as IGST, segmented).
     */
    public boolean isInternational() {
        return country != null && !country.isBlank() && !"India".equalsIgnoreCase(country.trim());
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

    public String getDiscountType() {
        return discountType;
    }

    public BigDecimal getDiscountValue() {
        return discountValue;
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
