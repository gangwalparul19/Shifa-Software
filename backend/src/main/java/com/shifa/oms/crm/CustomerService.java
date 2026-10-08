package com.shifa.oms.crm;

import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.crm.dto.CustomerDetailResponse;
import com.shifa.oms.crm.dto.CustomerOrderRow;
import com.shifa.oms.crm.dto.CustomerSummaryResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Customer CRM read/aggregate service ("operations depth" Feature 1).
 *
 * <p>A "customer" is identified by {@code customer_mobile}. This service builds
 * per-customer summaries by aggregating the {@code orders} table (via
 * {@link CustomerRepository#aggregate}) and enriches each row with a
 * {@code registered} flag by batch-resolving which mobiles map to a registered
 * {@link Role#CUSTOMER} account. The detail view reuses the existing order finder
 * to return a customer's order history as compact rows.
 *
 * <p>No new table is introduced — this is pure aggregation over existing data.
 *
 * <p><strong>Salesperson scoping (Req 5.4, 5.5).</strong> Because a customer is
 * derived purely from orders, the same {@link SalespersonScopeResolver} rule the
 * order/report modules use is applied here: when the caller is a
 * {@code SALESPERSON}, both the list aggregation and the detail lookup are
 * constrained to orders they created ({@code created_by = currentUserId}), so a
 * salesperson only sees their own customers and cannot open a customer outside
 * their scope (out-of-scope → 404). ADMIN / ACCOUNTANT remain unscoped.
 */
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;
    private final SalespersonScopeResolver scopeResolver;

    public CustomerService(CustomerRepository customerRepository,
                           OrderRepository orderRepository,
                           UserRepository userRepository,
                           CurrentUserService currentUserService,
                           SalespersonScopeResolver scopeResolver) {
        this.customerRepository = customerRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.currentUserService = currentUserService;
        this.scopeResolver = scopeResolver;
    }

    /**
     * The {@code created_by} constraint for the current caller, or {@code null}
     * when unscoped (ADMIN / ACCOUNTANT). A {@code SALESPERSON} resolves to their
     * own user id so every CRM query is filtered to orders they created.
     */
    private Long scopeConstraint() {
        return currentUserService.currentUser()
                .flatMap(scopeResolver::creatorConstraint)
                .orElse(null);
    }

    /**
     * Paged customer summaries aggregated across orders, filtered by an optional
     * {@code q} (name/mobile substring) and sorted by the caller's {@link Pageable}.
     *
     * @param q        name/mobile substring filter (nullable → all customers)
     * @param pageable page / size / sort
     */
    @Transactional(readOnly = true)
    public PageResponse<CustomerSummaryResponse> list(String q, Pageable pageable) {
        // Aggregate all matching customers, then sort + paginate in memory. This
        // avoids Spring Data's native-query sort qualifying the SELECT alias with
        // the table alias (o.totalSpent), which MySQL rejects. The customer set is
        // small, so this is inexpensive and robust.
        List<CustomerSummaryProjection> rows =
                customerRepository.aggregateAll(blankToNull(q), scopeConstraint());

        // Batch-resolve which mobiles map to a registered account.
        List<String> mobiles = rows.stream()
                .map(CustomerSummaryProjection::getMobile)
                .filter(m -> m != null && !m.isBlank())
                .toList();
        Set<String> registered = mobiles.isEmpty()
                ? Set.of()
                : new HashSet<>(userRepository.findCustomerMobilesIn(mobiles));

        List<CustomerSummaryResponse> all = new ArrayList<>(rows.size());
        for (CustomerSummaryProjection p : rows) {
            all.add(toSummary(p, registered.contains(p.getMobile())));
        }

        Comparator<CustomerSummaryResponse> comparator = comparatorFor(pageable.getSort());
        if (comparator != null) {
            all.sort(comparator);
        }

        int total = all.size();
        int start = (int) Math.min(pageable.getOffset(), total);
        int end = Math.min(start + pageable.getPageSize(), total);
        List<CustomerSummaryResponse> content = all.subList(start, end);
        Page<CustomerSummaryResponse> page = new PageImpl<>(content, pageable, total);
        return PageResponse.of(page);
    }

    /**
     * Builds an in-memory comparator for the requested sort (whitelisted fields
     * only: totalSpent / orderCount / lastOrderAt / firstOrderAt / mobile),
     * defaulting to newest-activity-first. Nulls sort last.
     */
    private static Comparator<CustomerSummaryResponse> comparatorFor(Sort sort) {
        Sort.Order order = (sort == null || sort.isEmpty()) ? null : sort.iterator().next();
        String property = order != null ? order.getProperty() : "lastOrderAt";
        Comparator<CustomerSummaryResponse> base = switch (property) {
            case "totalSpent" -> Comparator.comparing(CustomerSummaryResponse::totalSpent,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "orderCount" -> Comparator.comparingLong(CustomerSummaryResponse::orderCount);
            case "firstOrderAt" -> Comparator.comparing(CustomerSummaryResponse::firstOrderAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "mobile" -> Comparator.comparing(CustomerSummaryResponse::mobile,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(CustomerSummaryResponse::lastOrderAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        // Default direction is descending (most recent / highest first) when unspecified.
        boolean descending = order == null || order.isDescending();
        return descending ? base.reversed() : base;
    }

    /**
     * A single customer's detail by mobile: the aggregated summary plus their
     * order history (newest first). A 404 is raised when no order exists for the
     * mobile (a customer only "exists" through their orders).
     */
    @Transactional(readOnly = true)
    public CustomerDetailResponse get(String mobile) {
        String key = mobile == null ? "" : mobile.trim();
        List<OrderEntity> orders = orderRepository.findByCustomerMobileOrderByCreatedAtDesc(key);
        // Salesperson scoping (Req 5.5): restrict the history to orders this
        // salesperson created. A customer whose orders all fall outside the
        // caller's scope is indistinguishable from one that does not exist (404).
        Long createdBy = scopeConstraint();
        if (createdBy != null) {
            orders = orders.stream()
                    .filter(order -> createdBy.equals(order.getCreatedBy()))
                    .toList();
        }
        if (orders.isEmpty()) {
            throw new ResourceNotFoundException(
                    "No customer found for mobile " + key + ".");
        }

        boolean registered = userRepository.existsByMobileAndRole(key, Role.CUSTOMER);
        CustomerSummaryResponse summary = summariseFromOrders(key, orders, registered);
        List<CustomerOrderRow> rows = orders.stream().map(CustomerOrderRow::from).toList();
        return new CustomerDetailResponse(summary, rows);
    }

    // --- Internal helpers ---------------------------------------------------

    private static CustomerSummaryResponse toSummary(CustomerSummaryProjection p, boolean registered) {
        long orderCount = p.getOrderCount();
        BigDecimal totalSpent = p.getTotalSpent() != null ? p.getTotalSpent() : BigDecimal.ZERO;
        return new CustomerSummaryResponse(
                p.getMobile(),
                p.getName(),
                registered,
                orderCount,
                totalSpent,
                p.getLastOrderAt(),
                p.getFirstOrderAt(),
                orderCount > 1);
    }

    /**
     * Builds a summary from a customer's order list (used by the detail view).
     * Orders arrive newest-first from the finder, so the first row carries the
     * latest name / last-order timestamp and the last row the first-order one.
     */
    private static CustomerSummaryResponse summariseFromOrders(String mobile,
                                                               List<OrderEntity> orders,
                                                               boolean registered) {
        long orderCount = orders.size();
        BigDecimal totalSpent = BigDecimal.ZERO;
        for (OrderEntity order : orders) {
            if (order.getTotalAmount() != null) {
                totalSpent = totalSpent.add(order.getTotalAmount());
            }
        }
        OrderEntity newest = orders.get(0);
        OrderEntity oldest = orders.get(orders.size() - 1);
        LocalDateTime lastOrderAt = newest.getCreatedAt();
        LocalDateTime firstOrderAt = oldest.getCreatedAt();
        return new CustomerSummaryResponse(
                mobile,
                newest.getCustomerName(),
                registered,
                orderCount,
                totalSpent,
                lastOrderAt,
                firstOrderAt,
                orderCount > 1);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
