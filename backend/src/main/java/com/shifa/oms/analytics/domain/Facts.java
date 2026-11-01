package com.shifa.oms.analytics.domain;

import com.shifa.oms.order.DeliveryMethod;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.RejectReason;
import com.shifa.oms.order.RtoReason;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * The narrow, flat rows the growth analytics are computed from. They are loaded
 * with one projection query each and handed to the pure engines in this package,
 * so every figure can be reproduced from plain data.
 */
public final class Facts {

    private Facts() {
    }

    /** Orders that are not sales: rejected, payment-rejected and cancelled. */
    public static final Set<OrderStatus> LOST_STATUSES =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.PAYMENT_REJECTED, OrderStatus.CANCELLED);

    /** Statuses that mean the parcel reached the customer. */
    public static final Set<OrderStatus> DELIVERED_STATUSES =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    /** Statuses that mean delivery failed or the parcel came back. */
    public static final Set<OrderStatus> FAILED_STATUSES = EnumSet.of(
            OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED, OrderStatus.RTO, OrderStatus.REDISPATCH);

    /** The mobile the Shopify import stores when the buyer gave none; never a real customer. */
    public static final String PLACEHOLDER_MOBILE = "0000000000";

    /** Whether a mobile identifies a real customer (10 digits, not the import placeholder). */
    public static boolean isRealMobile(String mobile) {
        if (mobile == null || mobile.length() != 10 || PLACEHOLDER_MOBILE.equals(mobile)) {
            return false;
        }
        for (int i = 0; i < mobile.length(); i++) {
            if (!Character.isDigit(mobile.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A stable, one-way customer key for datasets that should not carry the mobile
     * number. The same mobile and salt always give the same key, so tables join.
     */
    public static String customerKey(String mobile, String salt) {
        if (!isRealMobile(mobile)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(((salt == null ? "" : salt) + ":" + mobile).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(Character.forDigit((hash[i] >> 4) & 0xF, 16)).append(Character.forDigit(hash[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** One order. */
    public record OrderFact(
            long id,
            String code,
            LocalDateTime createdAt,
            String mobile,
            String customerName,
            String state,
            String city,
            String postalCode,
            String country,
            OrderStatus status,
            PaymentStatus payment,
            OrderSource source,
            LeadSource leadSource,
            DeliveryMethod deliveryMethod,
            BigDecimal total,
            BigDecimal discount,
            BigDecimal received,
            BigDecimal cod,
            Long createdBy,
            RejectReason rejectReason,
            RtoReason rtoReason) {

        /** A sale: anything that is not rejected, payment-rejected or cancelled. */
        public boolean isSale() {
            return status != null && !LOST_STATUSES.contains(status);
        }

        public boolean isLost() {
            return status != null && LOST_STATUSES.contains(status);
        }

        public boolean isDelivered() {
            return status != null && DELIVERED_STATUSES.contains(status);
        }

        public boolean isFailed() {
            return status != null && FAILED_STATUSES.contains(status);
        }

        /** The order date in IST (stored times are already IST). */
        public LocalDate date() {
            return createdAt == null ? null : createdAt.toLocalDate();
        }

        /** The mobile when it identifies a real customer, else {@code null}. */
        public String customerMobile() {
            return isRealMobile(mobile) ? mobile : null;
        }
    }

    /** One order line, with the few order fields the product analytics need. */
    public record LineFact(
            long orderId,
            String orderCode,
            LocalDateTime createdAt,
            String mobile,
            OrderStatus orderStatus,
            Long productId,
            String productName,
            int quantity,
            BigDecimal rate,
            BigDecimal lineTotal) {

        public boolean isSale() {
            return orderStatus != null && !LOST_STATUSES.contains(orderStatus);
        }

        /** A key that treats catalogue products by id and unmatched lines by name. */
        public String productKey() {
            if (productId != null) {
                return "p" + productId;
            }
            return "n:" + (productName == null ? "" : productName.trim().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** One customer, summed over every sale they ever made. */
    public record CustomerFact(
            String mobile,
            String name,
            long orders,
            BigDecimal revenue,
            LocalDateTime firstOrderAt,
            LocalDateTime lastOrderAt) {
    }

    /** The first time an order entered a status. */
    public record StatusStamp(long orderId, OrderStatus status, LocalDateTime at) {
    }
}
