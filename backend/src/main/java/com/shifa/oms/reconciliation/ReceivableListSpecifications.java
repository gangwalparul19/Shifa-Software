package com.shifa.oms.reconciliation;

import com.shifa.oms.reconciliation.domain.ReceivableType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Builds the {@link Specification} for the admin reconciliation receivables
 * list/table (ROADMAP 2.2 "Wave 2" server-side filtering), extending the
 * existing courier/type filters with a settled flag, a {@code created_at} date
 * range, and an id-set restriction used to implement the free-text {@code q}
 * search (resolved to matching order ids by the service).
 *
 * <p>All filters are optional and AND-combined. The {@code orderIds} filter is
 * three-valued: {@code null} means "not filtering by q"; a non-null but empty
 * collection means "q matched no orders" and therefore matches nothing.
 */
public final class ReceivableListSpecifications {

    private ReceivableListSpecifications() {
    }

    public static Specification<ReceivableEntity> build(Long courierCompanyId,
                                                        ReceivableType type,
                                                        Boolean settled,
                                                        LocalDate from, LocalDate to,
                                                        Collection<Long> orderIds) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (courierCompanyId != null) {
                predicates.add(cb.equal(root.get("courierCompanyId"), courierCompanyId));
            }
            if (type != null) {
                predicates.add(cb.equal(root.get("type"), type));
            }
            if (settled != null) {
                predicates.add(cb.equal(root.get("settled"), settled));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(
                        root.get("createdAt"), from.atStartOfDay()));
            }
            if (to != null) {
                LocalDateTime endOfDay = LocalDateTime.of(to, LocalTime.MAX);
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endOfDay));
            }
            if (orderIds != null) {
                predicates.add(orderIds.isEmpty()
                        ? cb.disjunction()
                        : root.get("orderId").in(orderIds));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
