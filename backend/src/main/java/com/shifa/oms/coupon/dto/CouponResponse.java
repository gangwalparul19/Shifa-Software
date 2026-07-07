package com.shifa.oms.coupon.dto;

import com.shifa.oms.coupon.Coupon;
import com.shifa.oms.coupon.domain.CouponType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin view of a coupon (Phase D), returned by the management endpoints and
 * mirrored by the admin coupons table.
 */
public record CouponResponse(
        Long id,
        String code,
        String description,
        CouponType type,
        BigDecimal value,
        BigDecimal minCartAmount,
        BigDecimal maxDiscountAmount,
        boolean active,
        LocalDateTime startsAt,
        LocalDateTime endsAt,
        Integer usageLimit,
        int usedCount,
        Integer perCustomerLimit,
        LocalDateTime createdAt) {

    public static CouponResponse from(Coupon c) {
        return new CouponResponse(
                c.getId(),
                c.getCode(),
                c.getDescription(),
                c.getType(),
                c.getValue(),
                c.getMinCartAmount(),
                c.getMaxDiscountAmount(),
                c.isActive(),
                c.getStartsAt(),
                c.getEndsAt(),
                c.getUsageLimit(),
                c.getUsedCount(),
                c.getPerCustomerLimit(),
                c.getCreatedAt());
    }
}
