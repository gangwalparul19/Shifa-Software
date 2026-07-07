package com.shifa.oms.order;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the {@link Specification} for the admin orders list/table
 * (ROADMAP 2.2 "Wave 2" server-side filtering). Every filter is optional and
 * additive (AND-combined); a {@code null}/blank filter contributes nothing so
 * an empty filter set matches all orders.
 *
 * <p>Supported filters:
 * <ul>
 *   <li>{@code q} — case-insensitive substring over order code, customer name,
 *       or customer mobile;</li>
 *   <li>{@code status} — exact order lifecycle status;</li>
 *   <li>{@code paymentStatus} — exact payment status;</li>
 *   <li>{@code from}/{@code to} — inclusive {@code created_at} date range.</li>
 * </ul>
 */
public final class OrderListSpecifications {

    private OrderListSpecifications() {
    }

    public static Specification<OrderEntity> build(String q, OrderStatus status,
                                                   PaymentStatus paymentStatus,
                                                   LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("orderCode")), like),
                        cb.like(cb.lower(root.get("customerName")), like),
                        cb.like(cb.lower(root.get("customerMobile")), like)));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("orderStatus"), status));
            }
            if (paymentStatus != null) {
                predicates.add(cb.equal(root.get("paymentStatus"), paymentStatus));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                // Inclusive of the whole 'to' day.
                LocalDateTime endOfDay = LocalDateTime.of(to, LocalTime.MAX);
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endOfDay));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
