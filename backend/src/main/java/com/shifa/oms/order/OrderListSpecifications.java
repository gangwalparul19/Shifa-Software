package com.shifa.oms.order;

import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.statemachine.OrderStatus;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
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
 *       customer mobile, or the courier AWB / QuikShipX tracking id (matched via
 *       correlated subqueries against {@code order_shipments}/{@code courier_records});</li>
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
        return build(q, status, statusGroup, paymentStatus, from, to, creatorIds, null);
    }

    /**
     * As the {@code creatorIds} canonical builder with an additional exact
     * {@link OrderSource} filter: when {@code source} is non-null the result is
     * restricted to {@code source = source} (e.g. only Shopify-imported orders).
     * Orthogonal to status/payment; AND-combined with every other filter.
     */
    public static Specification<OrderEntity> build(String q, OrderStatus status,
                                                   OrderStatusGroup statusGroup,
                                                   PaymentStatus paymentStatus,
                                                   LocalDate from, LocalDate to,
                                                   Collection<Long> creatorIds,
                                                   OrderSource source) {
        return build(q, status, statusGroup, paymentStatus, from, to, creatorIds, source, null);
    }

    /**
     * As the {@code source} canonical builder, with an additional
     * {@code excludeSource} filter: when non-null, rows matching that source are
     * excluded (e.g. "Portal" = {@code excludeSource=SHOPIFY}). If BOTH
     * {@code source} and {@code excludeSource} are set, {@code source} wins
     * (an exact-match always trumps an exclusion).
     */
    public static Specification<OrderEntity> build(String q, OrderStatus status,
                                                   OrderStatusGroup statusGroup,
                                                   PaymentStatus paymentStatus,
                                                   LocalDate from, LocalDate to,
                                                   Collection<Long> creatorIds,
                                                   OrderSource source,
                                                   OrderSource excludeSource) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (source != null) {
                predicates.add(cb.equal(root.get("source"), source));
            } else if (excludeSource != null) {
                predicates.add(cb.notEqual(root.get("source"), excludeSource));
            }
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
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("orderCode")), like));
                any.add(cb.like(cb.lower(root.get("customerName")), like));
                any.add(cb.like(cb.lower(root.get("customerMobile")), like));
                // AWB / tracking id — searchable even though the AWB lives in a
                // separate table (order_shipments for QuikShipX, courier_records
                // for the legacy courier module). Correlated EXISTS subqueries keep
                // OrderEntity free of a courier relationship. The QuikShipX
                // shipper_order_id (the "Client Order ID" on remittance sheets) is
                // matched too, so pasting either the AWB or the QuikShip id finds
                // the order.
                any.add(awbMatch(query, cb, root, like, OrderShipment.class, "awb"));
                any.add(awbMatch(query, cb, root, like, OrderShipment.class, "shipperOrderId"));
                any.add(awbMatch(query, cb, root, like, CourierRecord.class, "awb"));
                predicates.add(cb.or(any.toArray(new Predicate[0])));
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

    /**
     * An {@code EXISTS} predicate: true when a row of {@code shipmentType} exists
     * whose {@code orderId} equals this order's id and whose {@code field} matches
     * the {@code like} pattern (case-insensitive). Used to make the AWB / tracking
     * id searchable from the orders list without mapping a courier relationship
     * onto {@link OrderEntity}.
     */
    private static <T> Predicate awbMatch(jakarta.persistence.criteria.CriteriaQuery<?> query,
                                          jakarta.persistence.criteria.CriteriaBuilder cb,
                                          Root<OrderEntity> root, String like,
                                          Class<T> shipmentType, String field) {
        Subquery<Long> sub = query.subquery(Long.class);
        Root<T> shipment = sub.from(shipmentType);
        sub.select(shipment.get("orderId"));
        sub.where(cb.and(
                cb.equal(shipment.get("orderId"), root.get("id")),
                cb.like(cb.lower(shipment.get(field)), like)));
        return cb.exists(sub);
    }
}
