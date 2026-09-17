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

    public AdminExceptionService(OrderRepository orderRepository,
                                 ReceivableRepository receivableRepository,
                                 InsightRepository insightRepository) {
        this.orderRepository = orderRepository;
        this.receivableRepository = receivableRepository;
        this.insightRepository = insightRepository;
    }

    /** Builds the current actionable exception list from existing persisted data. */
    @Transactional(readOnly = true)
    public AdminExceptionResponse list() {
        List<AdminExceptionResponse.AdminExceptionItem> items = new ArrayList<>();

        orderRepository.findByOrderStatusOrderByCreatedAtAsc(OrderStatus.PENDING_ADMIN_APPROVAL)
                .forEach(order -> addOrder(items, "APPROVAL", "HIGH", "Approval required",
                        "Order is waiting for admin approval.", order, null, "/approval-queue"));

        orderRepository.findByPaymentVerificationStatusOrderByCreatedAtAsc(PaymentVerificationStatus.PENDING)
                .forEach(order -> addOrder(items, "PAYMENT", "HIGH", "Payment verification pending",
                        order.getPaymentScreenshotKey() == null
                                ? "Review the payment amount and screenshot."
                                : "Review the uploaded payment screenshot before fulfilment.",
                        order, null, "/payments"));

        orderRepository.findByOrderStatusInOrderByCreatedAtDesc(FAILED_DELIVERY)
                .forEach(order -> addOrder(items, "DELIVERY", "HIGH", "Delivery needs a decision",
                        "Retry delivery or send the parcel through the RTO flow.", order, null, "/orders"));

        Map<Long, OrderEntity> ordersById = orderRepository.findAllById(
                        receivableRepository.findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(
                                        ReceivableType.CLAIM_RECEIVABLE)
                                .stream()
                                .map(ReceivableEntity::getOrderId)
                                .filter(java.util.Objects::nonNull)
                                .toList())
                .stream()
                .collect(Collectors.toMap(OrderEntity::getId, Function.identity(), (a, b) -> a));
        for (ReceivableEntity claim : receivableRepository
                .findByTypeAndSettledFalseOrderByCreatedAtDescIdDesc(ReceivableType.CLAIM_RECEIVABLE)) {
            OrderEntity order = ordersById.get(claim.getOrderId());
            if (order == null) {
                continue;
            }
            addOrder(items, "CLAIM", "HIGH", "Courier claim pending",
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
                        "/insights"))));

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
                actionPath));
    }
}
