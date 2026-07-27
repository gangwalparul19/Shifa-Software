package com.shifa.oms.order;

import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
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
        return build(q, status, paymentStatus, from, to, null);
    }

    /**
     * As {@link #build(String, OrderStatus, PaymentStatus, LocalDate, LocalDate)}
     * but additionally scopes the result to a single creator when {@code createdBy}
     * is non-null. This lets a salesperson browse the same paged/filtered orders
     * table restricted to the orders they punched (any status), while admins pass
     * {@code null} to see every order.
     */
    public static Specification<OrderEntity> build(String q, OrderStatus status,
                                                   PaymentStatus paymentStatus,
                                                   LocalDate from, LocalDate to,
                                                   Long createdBy) {
        return build(q, status, null, paymentStatus, from, to, createdBy);
    }

    /**
     * As {@link #build(String, OrderStatus, PaymentStatus, LocalDate, LocalDate, Long)}
     * with an additional coarse {@link OrderStatusGroup} filter: when
     * {@code statusGroup} is non-null the result is restricted to
     * {@code orderStatus IN (group members)}. The exact {@code status} and the
     * coarse {@code statusGroup} are independent and AND-combined if both are set
     * (the frontend sends at most one).
     */
    public static Specification<OrderEntity> build(String q, OrderStatus status,
                                                   OrderStatusGroup statusGroup,
                                                   PaymentStatus paymentStatus,
                                                   LocalDate from, LocalDate to,
                                                   Long createdBy) {
        return build(q, status, statusGroup, paymentStatus, from, to,
                createdBy == null ? null : List.of(createdBy));
    }

    /**
     * Canonical builder scoping to a <em>set</em> of creators: a
     * {@code SALESPERSON} passes a singleton of their own id, a {@code TEAM_LEAD}
     * passes their team's ids, and an unscoped admin/accountant passes
     * {@code null}. A present-but-empty collection means "scoped to nothing" and
     * matches no rows (a team lead with no assigned salespeople).
     */
    public static Specification<OrderEntity> build(String q, OrderStatus status,
                                                   OrderStatusGroup statusGroup,
                                                   PaymentStatus paymentStatus,
                                                   LocalDate from, LocalDate to,
                                                   Collection<Long> creatorIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (creatorIds != null) {
                if (creatorIds.isEmpty()) {
                    // Scoped to nothing (e.g. a team lead with no team) — match no rows.
                    predicates.add(cb.disjunction());
                } else {
                    predicates.add(root.get("createdBy").in(creatorIds));
                }
            }
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
            if (statusGroup != null && !statusGroup.statuses().isEmpty()) {
                predicates.add(root.get("orderStatus").in(statusGroup.statuses()));
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
