package com.shifa.oms.coupon;

import com.shifa.oms.coupon.domain.CouponPolicy;
import com.shifa.oms.coupon.domain.CouponType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

/**
 * A redeemable discount code, mapped to the {@code coupons} table (V6 migration).
 *
 * <p>The {@link #code} is always stored upper-cased and is unique. Applicability
 * is governed by {@link #active}, an optional validity window
 * ({@link #startsAt}/{@link #endsAt}), a {@link #minCartAmount}, and total
 * ({@link #usageLimit}/{@link #usedCount}) and {@link #perCustomerLimit} usage
 * caps. The discount itself is described by {@link #type} + {@link #value}
 * (with a {@link #maxDiscountAmount} cap for PERCENT). Evaluation is delegated
 * to the pure {@code CouponCalculator} via {@link #toPolicy()}.
 */
@Entity
@Table(name = "coupons")
public class Coupon {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, unique = true, length = 40)
    private String code;

    @Column(name = "description", length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private CouponType type;

    @Column(name = "value", nullable = false, precision = 12, scale = 2)
    private BigDecimal value = BigDecimal.ZERO;

    @Column(name = "min_cart_amount", precision = 12, scale = 2)
    private BigDecimal minCartAmount;

    @Column(name = "max_discount_amount", precision = 12, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    private int usedCount = 0;

    @Column(name = "per_customer_limit")
    private Integer perCustomerLimit;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    protected Coupon() {
        // Required by JPA.
    }

    public Coupon(String code, String description, CouponType type, BigDecimal value,
                  BigDecimal minCartAmount, BigDecimal maxDiscountAmount, boolean active,
                  LocalDateTime startsAt, LocalDateTime endsAt, Integer usageLimit,
                  Integer perCustomerLimit) {
        this.code = normalizeCode(code);
        this.description = description;
        this.type = type;
        this.value = value != null ? value : BigDecimal.ZERO;
        this.minCartAmount = minCartAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.active = active;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.usageLimit = usageLimit;
        this.perCustomerLimit = perCustomerLimit;
        this.usedCount = 0;
    }

    /** Upper-cases and trims a code for canonical storage / lookup. */
    public static String normalizeCode(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    @PrePersist
    void onCreate() {
        this.code = normalizeCode(this.code);
    }

    /** A pure snapshot of this coupon's rules for the {@code CouponCalculator}. */
    public CouponPolicy toPolicy() {
        return new CouponPolicy(code, type, value, minCartAmount, maxDiscountAmount,
                active, startsAt, endsAt, usageLimit, usedCount, perCustomerLimit);
    }

    /** Applies an admin edit to the mutable fields (code is re-normalized). */
    public void update(String code, String description, CouponType type, BigDecimal value,
                       BigDecimal minCartAmount, BigDecimal maxDiscountAmount, boolean active,
                       LocalDateTime startsAt, LocalDateTime endsAt, Integer usageLimit,
                       Integer perCustomerLimit) {
        this.code = normalizeCode(code);
        this.description = description;
        this.type = type;
        this.value = value != null ? value : BigDecimal.ZERO;
        this.minCartAmount = minCartAmount;
        this.maxDiscountAmount = maxDiscountAmount;
        this.active = active;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.usageLimit = usageLimit;
        this.perCustomerLimit = perCustomerLimit;
    }

    /** Records one redemption of this coupon. */
    public void incrementUsedCount() {
        this.usedCount += 1;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public CouponType getType() {
        return type;
    }

    public BigDecimal getValue() {
        return value;
    }

    public BigDecimal getMinCartAmount() {
        return minCartAmount;
    }

    public BigDecimal getMaxDiscountAmount() {
        return maxDiscountAmount;
    }

    public boolean isActive() {
        return active;
    }

    public LocalDateTime getStartsAt() {
        return startsAt;
    }

    public LocalDateTime getEndsAt() {
        return endsAt;
    }

    public Integer getUsageLimit() {
        return usageLimit;
    }

    public int getUsedCount() {
        return usedCount;
    }

    public Integer getPerCustomerLimit() {
        return perCustomerLimit;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
