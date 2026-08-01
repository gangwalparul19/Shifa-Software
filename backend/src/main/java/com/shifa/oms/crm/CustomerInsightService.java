package com.shifa.oms.crm;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.crm.domain.CustomerRiskCalculator;
import com.shifa.oms.crm.domain.CustomerRiskLevel;
import com.shifa.oms.crm.dto.CustomerMetrics;
import com.shifa.oms.crm.dto.CustomerNoteResponse;
import com.shifa.oms.crm.dto.CustomerOrderRow;
import com.shifa.oms.crm.dto.CustomerProfileResponse;
import com.shifa.oms.crm.dto.CustomerRiskResponse;
import com.shifa.oms.crm.dto.CustomerSummaryResponse;
import com.shifa.oms.crm.dto.StatusCount;
import com.shifa.oms.crm.dto.TopProductRow;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read + light-write service for the customer "360" CRM depth
 * (FEATURE-ROADMAP §1): the enriched profile (§1.1), the delivery-reliability
 * risk score (§1.2), and staff-managed tags/notes (§1.4).
 *
 * <p>Kept separate from the existing {@link CustomerService} (the list/detail
 * read layer) so that service — and its constructor, exercised by tests — stays
 * untouched. As there, a "customer" is derived from the {@code orders} table
 * keyed by {@code customer_mobile}, and the same {@link SalespersonScopeResolver}
 * rule applies: a {@code SALESPERSON} only sees/edits customers derived from
 * orders they created (out-of-scope → 404); {@code ADMIN}/{@code ACCOUNTANT}
 * are unscoped.
 */
@Service
public class CustomerInsightService {

    /** Concluded successful deliveries. */
    private static final Set<OrderStatus> DELIVERED_STATUSES =
            EnumSet.of(OrderStatus.DELIVERED, OrderStatus.COD_COLLECTED, OrderStatus.CLOSED);

    /** Concluded failed deliveries (the ones that drive risk). */
    private static final Set<OrderStatus> FAILED_STATUSES =
            EnumSet.of(OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED,
                    OrderStatus.RTO, OrderStatus.REDISPATCH);

    /** Orders that never shipped (rejected at approval / cancelled). */
    private static final Set<OrderStatus> CANCELLED_STATUSES =
            EnumSet.of(OrderStatus.REJECTED, OrderStatus.CANCELLED);

    /** How many products to surface on the profile. */
    private static final int TOP_PRODUCTS_LIMIT = 5;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final CustomerTagRepository tagRepository;
    private final CustomerNoteRepository noteRepository;
    private final CurrentUserService currentUserService;
    private final SalespersonScopeResolver scopeResolver;
    private final AuditService auditService;

    public CustomerInsightService(OrderRepository orderRepository,
                                  UserRepository userRepository,
                                  CustomerTagRepository tagRepository,
                                  CustomerNoteRepository noteRepository,
                                  CurrentUserService currentUserService,
                                  SalespersonScopeResolver scopeResolver,
                                  AuditService auditService) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.tagRepository = tagRepository;
        this.noteRepository = noteRepository;
        this.currentUserService = currentUserService;
        this.scopeResolver = scopeResolver;
        this.auditService = auditService;
    }

    // --- Profile (§1.1) -----------------------------------------------------

    /**
     * The full Customer 360 profile: summary + derived metrics + risk + top
     * products + status breakdown + tags + notes + order history. 404 when the
     * mobile has no orders in the caller's scope.
     */
    @Transactional(readOnly = true)
    public CustomerProfileResponse profile(String mobile) {
        String key = normalizeMobile(mobile);
        List<OrderEntity> orders = requireScopedOrders(key);
        boolean registered = userRepository.existsByMobileAndRole(key, Role.CUSTOMER);

        CustomerSummaryResponse summary = summarise(key, orders, registered);
        CustomerMetrics metrics = metrics(orders);
        CustomerRiskResponse risk = riskFrom(key, orders);
        List<TopProductRow> topProducts = topProducts(orders);
        List<StatusCount> statusBreakdown = statusBreakdown(orders);
        List<String> tags = tagsOf(key);
        List<CustomerNoteResponse> notes = noteRepository
                .findByCustomerMobileOrderByCreatedAtDesc(key).stream()
                .map(CustomerNoteResponse::from).toList();
        List<CustomerOrderRow> history = orders.stream().map(CustomerOrderRow::from).toList();

        return new CustomerProfileResponse(
                summary, metrics, risk, topProducts, statusBreakdown, tags, notes, history);
    }

    // --- Risk (§1.2) — used by the order-entry nudge ------------------------

    /**
     * A customer's delivery-reliability risk, keyed by mobile. Unlike
     * {@link #profile}, this NEVER 404s: a mobile with no orders in the caller's
     * scope is simply treated as a brand-new customer ({@code LOW}, 0 prior
     * orders), which is exactly what the order-entry form wants when a
     * salesperson types a new number.
     */
    @Transactional(readOnly = true)
    public CustomerRiskResponse risk(String mobile) {
        String key = normalizeMobile(mobile);
        List<OrderEntity> orders = scopedOrders(key);
        return riskFrom(key, orders);
    }

    // --- Tags (§1.4) --------------------------------------------------------

    /** Adds a segment tag to a customer (idempotent per tag); returns the updated tag list. */
    @Transactional
    public List<String> addTag(String mobile, String rawTag) {
        String key = normalizeMobile(mobile);
        requireScopedOrders(key); // authorise + ensure the customer exists in scope
        String tag = rawTag == null ? "" : rawTag.trim();
        if (tag.isEmpty()) {
            throw new IllegalArgumentException("tag is required");
        }
        if (!tagRepository.existsByCustomerMobileAndTagIgnoreCase(key, tag)) {
            Long actorId = currentUserService.currentUser().map(AuthPrincipal::userId).orElse(null);
            tagRepository.save(new CustomerTag(key, tag, actorId));
            auditService.record(AuditActions.CUSTOMER_TAG_ADDED, AuditActions.ENTITY_CUSTOMER,
                    key, "Tagged customer " + key + " '" + tag + "'");
        }
        return tagsOf(key);
    }

    /** Removes a segment tag from a customer; returns the updated tag list. */
    @Transactional
    public List<String> removeTag(String mobile, String rawTag) {
        String key = normalizeMobile(mobile);
        requireScopedOrders(key);
        String tag = rawTag == null ? "" : rawTag.trim();
        long removed = tagRepository.deleteByCustomerMobileAndTag(key, tag);
        if (removed > 0) {
            auditService.record(AuditActions.CUSTOMER_TAG_REMOVED, AuditActions.ENTITY_CUSTOMER,
                    key, "Removed tag '" + tag + "' from customer " + key);
        }
        return tagsOf(key);
    }

    // --- Notes (§1.1) -------------------------------------------------------

    /** Adds a note to a customer's timeline; returns the timeline (newest first). */
    @Transactional
    public List<CustomerNoteResponse> addNote(String mobile, String rawNote) {
        String key = normalizeMobile(mobile);
        requireScopedOrders(key);
        String note = rawNote == null ? "" : rawNote.trim();
        if (note.isEmpty()) {
            throw new IllegalArgumentException("note is required");
        }
        AuthPrincipal actor = currentUserService.currentUser().orElse(null);
        Long actorId = actor != null ? actor.userId() : null;
        String actorName = actor != null ? actor.username() : null;
        noteRepository.save(new CustomerNote(key, note, actorId, actorName));
        auditService.record(AuditActions.CUSTOMER_NOTE_ADDED, AuditActions.ENTITY_CUSTOMER,
                key, "Added a note to customer " + key);
        return noteRepository.findByCustomerMobileOrderByCreatedAtDesc(key).stream()
                .map(CustomerNoteResponse::from).toList();
    }

    // --- Internal helpers ---------------------------------------------------

    private List<String> tagsOf(String mobile) {
        return tagRepository.findByCustomerMobileOrderByTagAsc(mobile).stream()
                .map(CustomerTag::getTag).toList();
    }

    /** The {@code created_by} constraint for the caller, or null when unscoped. */
    private Long scopeConstraint() {
        return currentUserService.currentUser()
                .flatMap(scopeResolver::creatorConstraint)
                .orElse(null);
    }

    /** A customer's orders visible to the caller (may be empty), newest first. */
    private List<OrderEntity> scopedOrders(String mobile) {
        List<OrderEntity> orders = orderRepository.findByCustomerMobileOrderByCreatedAtDesc(mobile);
        Long createdBy = scopeConstraint();
        if (createdBy != null) {
            orders = orders.stream()
                    .filter(order -> createdBy.equals(order.getCreatedBy()))
                    .toList();
        }
        return orders;
    }

    /** Like {@link #scopedOrders} but 404s when the customer has none in scope. */
    private List<OrderEntity> requireScopedOrders(String mobile) {
        List<OrderEntity> orders = scopedOrders(mobile);
        if (orders.isEmpty()) {
            throw new ResourceNotFoundException("No customer found for mobile " + mobile + ".");
        }
        return orders;
    }

    private static CustomerSummaryResponse summarise(String mobile, List<OrderEntity> orders,
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
        return new CustomerSummaryResponse(
                mobile,
                newest.getCustomerName(),
                registered,
                orderCount,
                totalSpent,
                newest.getCreatedAt(),
                oldest.getCreatedAt(),
                orderCount > 1);
    }

    private static CustomerMetrics metrics(List<OrderEntity> orders) {
        long delivered = 0;
        long failed = 0;
        long cancelled = 0;
        long inFlight = 0;
        BigDecimal outstanding = BigDecimal.ZERO;
        for (OrderEntity order : orders) {
            OrderStatus status = order.getOrderStatus();
            if (DELIVERED_STATUSES.contains(status)) {
                delivered++;
            } else if (FAILED_STATUSES.contains(status)) {
                failed++;
            } else if (CANCELLED_STATUSES.contains(status)) {
                cancelled++;
            } else {
                inFlight++;
            }
            if (order.getCustomerOutstanding() != null) {
                outstanding = outstanding.add(order.getCustomerOutstanding());
            }
        }
        double successRate = 1.0 - CustomerRiskCalculator.failureRate(failed, delivered);
        long concluded = delivered + failed;
        if (concluded == 0) {
            successRate = 0.0; // no concluded deliveries yet — nothing to rate
        }
        return new CustomerMetrics(delivered, failed, inFlight, cancelled, successRate, outstanding);
    }

    private static CustomerRiskResponse riskFrom(String mobile, List<OrderEntity> orders) {
        long delivered = 0;
        long failed = 0;
        for (OrderEntity order : orders) {
            OrderStatus status = order.getOrderStatus();
            if (DELIVERED_STATUSES.contains(status)) {
                delivered++;
            } else if (FAILED_STATUSES.contains(status)) {
                failed++;
            }
        }
        CustomerRiskLevel level = CustomerRiskCalculator.assess(failed, delivered);
        double rate = CustomerRiskCalculator.failureRate(failed, delivered);
        long orderCount = orders.size();
        return new CustomerRiskResponse(
                mobile, level, failed, delivered, rate, orderCount, orderCount > 1,
                riskMessage(level, failed, orderCount));
    }

    private static String riskMessage(CustomerRiskLevel level, long failed, long orderCount) {
        return switch (level) {
            case HIGH -> failed + " past failed deliveries — strongly consider prepaid over COD.";
            case MEDIUM -> failed + " past failed delivery" + (failed == 1 ? "" : "ies")
                    + " — consider prepaid for this order.";
            case LOW -> orderCount == 0
                    ? "New customer — no delivery history yet."
                    : "Good delivery record.";
        };
    }

    private static List<TopProductRow> topProducts(List<OrderEntity> orders) {
        Map<String, long[]> qtyByKey = new LinkedHashMap<>();       // key -> [quantity]
        Map<String, BigDecimal> amountByKey = new LinkedHashMap<>();
        Map<String, Long> idByKey = new LinkedHashMap<>();
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (OrderEntity order : orders) {
            if (CANCELLED_STATUSES.contains(order.getOrderStatus())) {
                continue; // never-shipped orders are not "products bought"
            }
            for (OrderLineItem item : order.getLineItems()) {
                String key = item.getProductId() != null
                        ? "id:" + item.getProductId()
                        : "name:" + (item.getProductName() == null ? "" : item.getProductName());
                qtyByKey.computeIfAbsent(key, k -> new long[1])[0] += item.getQuantity();
                amountByKey.merge(key,
                        item.getLineTotal() != null ? item.getLineTotal() : BigDecimal.ZERO,
                        BigDecimal::add);
                idByKey.putIfAbsent(key, item.getProductId());
                nameByKey.putIfAbsent(key, item.getProductName());
            }
        }
        List<TopProductRow> rows = new ArrayList<>(qtyByKey.size());
        for (String key : qtyByKey.keySet()) {
            rows.add(new TopProductRow(
                    idByKey.get(key), nameByKey.get(key),
                    qtyByKey.get(key)[0], amountByKey.get(key)));
        }
        rows.sort(Comparator.comparing(TopProductRow::amount,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows.size() > TOP_PRODUCTS_LIMIT ? rows.subList(0, TOP_PRODUCTS_LIMIT) : rows;
    }

    private static List<StatusCount> statusBreakdown(List<OrderEntity> orders) {
        Map<OrderStatus, Long> counts = new java.util.EnumMap<>(OrderStatus.class);
        for (OrderEntity order : orders) {
            counts.merge(order.getOrderStatus(), 1L, Long::sum);
        }
        List<StatusCount> rows = new ArrayList<>(counts.size());
        for (OrderStatus status : OrderStatus.values()) { // lifecycle (declaration) order
            Long count = counts.get(status);
            if (count != null && count > 0) {
                rows.add(new StatusCount(status, count));
            }
        }
        return rows;
    }

    private static String normalizeMobile(String mobile) {
        return mobile == null ? "" : mobile.trim();
    }
}
