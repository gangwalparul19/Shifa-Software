package com.shifa.oms.coupon.dto;

import com.shifa.oms.coupon.domain.CouponType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin create/update payload for a coupon (Phase D). The code is required and
 * stored upper-cased; type is one of PERCENT / FLAT / FREE_SHIPPING; value is
 * the percent (PERCENT) or amount (FLAT) and ignored for FREE_SHIPPING. All
 * limit / window / cap fields are optional (null = no constraint). Deeper
 * type/value sanity (e.g. percent 0..100) is enforced in the service.
 */
public record CouponRequest(
        @NotBlank(message = "code is required")
        @Size(max = 40, message = "code must be at most 40 characters")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "code may only contain letters, digits, hyphen and underscore")
        String code,

        @Size(max = 255, message = "description must be at most 255 characters")
        String description,

        @NotNull(message = "type is required")
        CouponType type,

        @NotNull(message = "value is required")
        @DecimalMin(value = "0.0", message = "value must not be negative")
        BigDecimal value,

        @DecimalMin(value = "0.0", message = "minCartAmount must not be negative")
        BigDecimal minCartAmount,

        @DecimalMin(value = "0.0", message = "maxDiscountAmount must not be negative")
        BigDecimal maxDiscountAmount,

        boolean active,

        LocalDateTime startsAt,

        LocalDateTime endsAt,

        @Min(value = 1, message = "usageLimit must be at least 1")
        Integer usageLimit,

        @Min(value = 1, message = "perCustomerLimit must be at least 1")
        Integer perCustomerLimit
) {
}
