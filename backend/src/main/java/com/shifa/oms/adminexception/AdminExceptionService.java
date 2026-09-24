package com.shifa.oms.adminexception;

import com.shifa.oms.adminexception.dto.AdminExceptionResponse;
import com.shifa.oms.insights.InsightEntity;
import com.shifa.oms.insights.InsightRepository;
import com.shifa.oms.insights.domain.InsightSeverity;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.reconciliation.ReceivableEntity;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.reconciliation.domain.ReceivableType;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Combines existing operational queues into one read-only admin work list.
 * Mutation remains on the owning page and is never performed by this service.
 */
@Service
public class AdminExceptionService {

    private static final Set<OrderStatus> FAILED_DELIVERY = EnumSet.of(
            OrderStatus.CUSTOMER_REJECTED, OrderStatus.DELIVERY_FAILED);

    private final OrderRepository orderRepository;
    private final ReceivableRepository receivableRepository;
    private final InsightRepository insightRepository;
    /**
     * Staff directory (nullable): resolves each order's {@code created_by} to the
     * salesperson's display name for the exception rows. Null under the legacy
     * test constructor — the name is then omitted.
     */
    private final com.shifa.oms.auth.UserRepository userRepository;

    /** Legacy constructor (tests): no staff directory, so the salesperson name is omitted. */
    public AdminExceptionService(OrderRepository orderRepository,
                                 ReceivableRepository receivableRepository,
                                 InsightRepository insightRepository) {
        this(orderRepository, receivableRepository, insightRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminExceptionService(OrderRepository orderRepository,
                                 ReceivableRepository receivableRepository,
                                 InsightRepository insightRepository,
                                 com.shifa.oms.auth.UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.insightRepository = insightRepository;
        this.userRepository = userRepository;
    }

    /** Builds the current actionable exception list from existing persisted data. */
    @Transactional(readOnly = true)
    public AdminExceptionResponse list() {
        List<AdminExceptionResponse.AdminExceptionItem> items = new ArrayList<>();

        List<OrderEntity> pendingApproval =
                orderRepository.findByOrderStatusOrderByCreatedAtDesc(OrderStatus.PENDING_ADMIN_APPROVAL);
        List<OrderEntity> pendingPayment =
                orderRepository.findByPaymentVerificationStatusOrderByCreatedAtDesc(PaymentVerificationStatus.PENDING);
        List<OrderEntity> failedDelivery =
                orderRepository.findByOrderStatusInOrderByCreatedAtDesc(FAILED_DELIVERY);
        List<ReceivableEntity> pendingClaims = receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(ReceivableType.CLAIM_RECEIVABLE);
        Map<Long, OrderEntity> claimOrdersById = orderRepository.findAllById(
                        pendingClaims.stream()
                                .map(ReceivableEntity::getOrderId)
                                .filter(java.util.Objects::nonNull)
                                .toList())
                .stream()
                .collect(Collectors.toMap(OrderEntity::getId, Function.identity(), (a, b) -> a));

        // Resolve every order-backed row's salesperson (created_by) name in a single
        // query, so each exception shows who punched the order (no N+1).
        Map<Long, String> names = resolveSalespersonNames(
                pendingApproval, pendingPayment, failedDelivery, claimOrdersById.values());

        pendingApproval.forEach(order -> addOrder(items, names, "APPROVAL", "HIGH", "Approval required",
                "Order is waiting for admin approval.", order, null, "/approval-queue"));

        pendingPayment.forEach(order -> addOrder(items, names, "PAYMENT", "HIGH", "Payment verification pending",
                order.getPaymentScreenshotKey() == null
                        ? "Review the payment amount and screenshot."
                        : "Review the uploaded payment screenshot before fulfilment.",
                order, null, "/payments"));

        failedDelivery.forEach(order -> addOrder(items, names, "DELIVERY", "HIGH", "Delivery needs a decision",
                "Retry delivery or send the parcel through the RTO flow.", order, null, "/orders"));

        for (ReceivableEntity claim : pendingClaims) {
            OrderEntity order = claimOrdersById.get(claim.getOrderId());
            if (order == null) {
                continue;
            }
            addOrder(items, names, "CLAIM", "HIGH", "Courier claim pending",
                    "File or settle the loss claim for this order.", order, claim.getAmount(), "/reconciliation");
        }

        insightRepository.findMaxComputedDate().ifPresent(date -> insightRepository
                .findByComputedDateAndDismissedFalse(date)
                .stream()
                .filter(i -> i.getSeverity() == InsightSeverity.WARNING || i.getSeverity() == InsightSeverity.DANGER)
                .forEach(i -> items.add(new AdminExceptionResponse.AdminExceptionItem(
                        "INSIGHT",
                        i.getSeverity().name(),
                        i.getTitle(),
                        i.getDetail(),
                        null,
                        null,
                        i.getScopeLabel(),
                        null,
                        null,
                        i.getMetricValue(),
                        i.getCreatedAt(),
                        "/insights",
                        null))));

        items.sort(Comparator
                .comparingInt((AdminExceptionResponse.AdminExceptionItem item) -> severityRank(item.severity()))
                .thenComparing(AdminExceptionResponse.AdminExceptionItem::createdAt,
                        Comparator.nullsLast(Comparator.naturalOrder())));

        Map<String, Long> counts = new LinkedHashMap<>();
        items.forEach(item -> counts.merge(item.category(), 1L, Long::sum));
        return new AdminExceptionResponse(items.size(), counts, List.copyOf(items));
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "HIGH", "DANGER" -> 0;
            case "MEDIUM", "WARNING" -> 1;
            default -> 2;
        };
    }

    private static void addOrder(List<AdminExceptionResponse.AdminExceptionItem> items,
                                 Map<Long, String> names,
                                 String category,
                                 String severity,
                                 String title,
                                 String detail,
                                 OrderEntity order,
                                 java.math.BigDecimal amount,
                                 String actionPath) {
        items.add(new AdminExceptionResponse.AdminExceptionItem(
                category,
                severity,
                title,
                detail,
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                order.getCustomerMobile(),
                order.getOrderStatus(),
                amount,
                order.getCreatedAt(),
                actionPath,
                order.getCreatedBy() == null ? null : names.get(order.getCreatedBy())));
    }

    /**
     * Batch-resolves the display names of the salespeople who created the given
     * order lists (full name, else username), mirroring the packing pattern: one
     * {@code findAllById} query. Empty when there is no staff directory
     * (test/legacy) or no creators, so the name is simply omitted.
     */
    @SafeVarargs
    private final Map<Long, String> resolveSalespersonNames(java.util.Collection<OrderEntity>... orderGroups) {
        Map<Long, String> names = new LinkedHashMap<>();
        if (userRepository == null) {
            return names;
        }
        Set<Long> ids = new java.util.HashSet<>();
        for (java.util.Collection<OrderEntity> group : orderGroups) {
            for (OrderEntity o : group) {
                if (o.getCreatedBy() != null) {
                    ids.add(o.getCreatedBy());
                }
            }
        }
        if (ids.isEmpty()) {
            return names;
        }
        for (com.shifa.oms.auth.User u : userRepository.findAllById(ids)) {
            String name = (u.getFullName() != null && !u.getFullName().isBlank())
                    ? u.getFullName() : u.getUsername();
            names.put(u.getId(), name);
        }
        return names;
    }
}
