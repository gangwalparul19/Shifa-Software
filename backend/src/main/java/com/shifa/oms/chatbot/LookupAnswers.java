package com.shifa.oms.chatbot;

import com.shifa.oms.chatbot.dto.ChatbotAnswer.Table;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.order.OrderStatusHistory;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.shifa.oms.chatbot.ChatbotFormat.dateTime;
import static com.shifa.oms.chatbot.ChatbotFormat.inr;
import static com.shifa.oms.chatbot.ChatbotFormat.paymentLabel;
import static com.shifa.oms.chatbot.ChatbotFormat.plural;
import static com.shifa.oms.chatbot.ChatbotFormat.stageLabel;
import static com.shifa.oms.chatbot.ChatbotFormat.statusLabel;
import static com.shifa.oms.chatbot.ChatbotFormat.verificationLabel;

/**
 * Answers about one specific order (found by order number, courier tracking
 * number, QuikShip id, or the last characters of an order number) or about one
 * customer's recent orders (found by mobile number). Read-only.
 */
@Component
public class LookupAnswers {

    /** How many of a customer's orders to list. */
    private static final int CUSTOMER_ORDERS = 5;
    /** How many recent status changes to show for an order. */
    private static final int TIMELINE_ROWS = 6;

    private final ChatbotQueryRepository queries;
    private final OrderShipmentRepository shipments;
    private final CourierRecordRepository courierRecords;
    private final CourierCompanyRepository courierCompanies;

    public LookupAnswers(ChatbotQueryRepository queries, OrderShipmentRepository shipments,
                         CourierRecordRepository courierRecords, CourierCompanyRepository courierCompanies) {
        this.queries = queries;
        this.shipments = shipments;
        this.courierRecords = courierRecords;
        this.courierCompanies = courierCompanies;
    }

    // ------------------------------------------------------------------ order

    /** An order number such as SHR-20260925-WJS7. */
    ChatbotSection byOrderNumber(String orderCode, ChatbotDirectory dir) {
        Optional<OrderEntity> order = queries.findByOrderCode(orderCode);
        if (order.isPresent()) {
            return orderDetails(order.get(), dir);
        }
        // Staff sometimes type the number without the date part or with a typo in it.
        String tail = orderCode.substring(orderCode.lastIndexOf('-') + 1);
        List<OrderEntity> byTail = tail.length() >= 4
                ? queries.findTop5ByOrderCodeEndingWithIgnoreCaseOrderByCreatedAtDesc(tail) : List.of();
        if (byTail.size() == 1) {
            ChatbotSection s = orderDetails(byTail.get(0), dir);
            List<String> lines = new ArrayList<>();
            lines.add("There is no order numbered " + orderCode + ". The closest match ends the same way:");
            lines.addAll(s.lines());
            return new ChatbotSection(lines, s.table());
        }
        return notFound(orderCode, byTail);
    }

    /** A courier / QuikShip tracking number, QuikShip order id, or the end of an order number. */
    ChatbotSection byReference(String reference, ChatbotDirectory dir) {
        Optional<OrderEntity> viaCourier = courierRecords.findByAwb(reference)
                .flatMap(r -> queries.findById(r.getOrderId()));
        if (viaCourier.isPresent()) {
            return orderDetails(viaCourier.get(), dir);
        }
        Optional<OrderShipment> shipment = shipments.findByAwb(reference);
        if (shipment.isEmpty()) {
            shipment = shipments.findByShipperOrderId(reference);
        }
        if (shipment.isPresent()) {
            Optional<OrderEntity> order = queries.findById(shipment.get().getOrderId());
            if (order.isPresent()) {
                return orderDetails(order.get(), dir);
            }
        }
        List<OrderEntity> byTail = reference.length() >= 4
                ? queries.findTop5ByOrderCodeEndingWithIgnoreCaseOrderByCreatedAtDesc(reference) : List.of();
        if (byTail.size() == 1) {
            return orderDetails(byTail.get(0), dir);
        }
        return notFound(reference, byTail);
    }

    private ChatbotSection notFound(String what, List<OrderEntity> candidates) {
        List<String> lines = new ArrayList<>();
        if (candidates.isEmpty()) {
            lines.add("I couldn't find an order, tracking number or QuikShip id matching \u201C" + what + "\u201D.");
            lines.add("Try the full order number, for example the one shown on the Orders page.");
            return ChatbotSection.ofLines(lines);
        }
        lines.add("More than one order ends with \u201C" + what + "\u201D. Which one do you mean?");
        return new ChatbotSection(lines, orderTable(candidates));
    }

    private ChatbotSection orderDetails(OrderEntity o, ChatbotDirectory dir) {
        List<String> lines = new ArrayList<>();
        OrderStatus status = o.getOrderStatus();
        OrderStatusGroup stage = OrderStatusGroup.groupOf(status);
        lines.add("Order " + o.getOrderCode() + " is " + statusLabel(status)
                + (stage != null ? " (stage: " + stageLabel(stage) + ")" : "") + ".");

        List<OrderStatusHistory> history = o.getStatusHistory();
        if (!history.isEmpty()) {
            OrderStatusHistory last = history.get(history.size() - 1);
            lines.add("Last update: " + statusLabel(last.getToStatus()) + ", " + dateTime(last.getChangedAt()) + ".");
        }

        lines.add("Customer: " + o.getCustomerName() + ", " + o.getCity() + " (" + o.getCustomerMobile() + ").");
        lines.add("Placed " + dateTime(o.getCreatedAt()) + " by " + placedBy(o, dir) + " through "
                + channelOf(o.getSource()) + ".");

        List<String> items = new ArrayList<>();
        for (OrderLineItem li : o.getLineItems()) {
            items.add(li.getQuantity() + " x " + li.getProductName());
        }
        if (!items.isEmpty()) {
            lines.add("Items: " + String.join(", ", items) + ".");
        }

        lines.add("Total " + inr(o.getTotalAmount()) + ". Paid upfront " + inr(o.getAmountReceived())
                + "; to collect on delivery " + inr(o.getCodAmount()) + ". Payment: " + paymentLabel(o.getPaymentStatus())
                + (o.getPaymentVerificationStatus() != null
                ? " (payment check: " + verificationLabel(o.getPaymentVerificationStatus()) + ")" : "") + ".");

        if (status == OrderStatus.REJECTED || status == OrderStatus.PAYMENT_REJECTED) {
            String reason = o.getRejectionReason();
            lines.add("Reason: " + (o.getRejectReason() != null ? o.getRejectReason().name().replace('_', ' ').toLowerCase() : "not given")
                    + (reason != null && !reason.isBlank() ? " - " + reason : "") + ".");
        }

        lines.addAll(shippingLines(o));

        Table table = null;
        if (history.size() > 1) {
            List<List<String>> rows = new ArrayList<>();
            for (int i = history.size() - 1; i >= 0 && rows.size() < TIMELINE_ROWS; i--) {
                OrderStatusHistory h = history.get(i);
                rows.add(List.of(dateTime(h.getChangedAt()), statusLabel(h.getToStatus()), h.getActor()));
            }
            table = new Table(List.of("When", "Status", "By"), rows);
        }
        return new ChatbotSection(lines, table);
    }

    private List<String> shippingLines(OrderEntity o) {
        List<String> lines = new ArrayList<>();
        if (o.isInHouseDelivery()) {
            lines.add("Delivery: in-house (Ishika Enterprise), no courier partner.");
            return lines;
        }
        Optional<OrderShipment> shipment = shipments.findByOrderId(o.getId());
        if (shipment.isPresent()) {
            OrderShipment s = shipment.get();
            String status = s.getQuikShipXStatus() == null ? "not yet confirmed" : s.getQuikShipXStatus();
            lines.add("QuikShip: " + status
                    + (s.getAwb() != null ? ", tracking number " + s.getAwb() : ", no tracking number yet")
                    + (s.getShipperOrderId() != null ? ", QuikShip order id " + s.getShipperOrderId() : "") + ".");
            return lines;
        }
        Optional<CourierRecord> record = courierRecords.findByOrderId(o.getId());
        if (record.isPresent() && record.get().getAwb() != null) {
            String courier = record.get().getCourierCompanyId() == null ? "courier"
                    : courierCompanies.findById(record.get().getCourierCompanyId())
                    .map(c -> c.getName()).orElse("courier");
            lines.add("Courier: " + courier + ", tracking number " + record.get().getAwb()
                    + (record.get().getEstimatedDelivery() != null
                    ? ", expected " + record.get().getEstimatedDelivery() : "") + ".");
        } else {
            lines.add("No courier or tracking number has been assigned yet.");
        }
        return lines;
    }

    private static String placedBy(OrderEntity o, ChatbotDirectory dir) {
        if (o.getCreatedBy() == null) {
            return o.getSource() == OrderSource.SHOPIFY ? "Shopify (automatic import)" : "an unknown user";
        }
        return dir.staffNames().getOrDefault(o.getCreatedBy(), "user #" + o.getCreatedBy());
    }

    private static String channelOf(OrderSource source) {
        return switch (source) {
            case SHOPIFY -> "Shopify";
            case STORE -> "in-shop (POS) sale";
            default -> "the portal";
        };
    }

    // ------------------------------------------------------------------ customer

    /** A customer's most recent orders, found by mobile number. */
    ChatbotSection byMobile(String mobile, ChatbotDirectory dir) {
        List<OrderEntity> orders = queries.findTop5ByCustomerMobileOrderByCreatedAtDescIdDesc(mobile);
        if (orders.isEmpty()) {
            return ChatbotSection.ofLines(List.of());
        }
        long total = queries.countByCustomerMobile(mobile);
        OrderEntity latest = orders.get(0);
        List<String> lines = new ArrayList<>();
        lines.add(latest.getCustomerName() + " (" + mobile + ") has placed " + plural(total, "order") + "."
                + (total > CUSTOMER_ORDERS ? " Here are the latest " + CUSTOMER_ORDERS + ":" : ""));
        return new ChatbotSection(lines, orderTable(orders));
    }

    private static Table orderTable(List<OrderEntity> orders) {
        List<List<String>> rows = new ArrayList<>();
        for (OrderEntity o : orders) {
            rows.add(List.of(o.getOrderCode(), dateTime(o.getCreatedAt()), statusLabel(o.getOrderStatus()),
                    inr(o.getTotalAmount()), paymentLabel(o.getPaymentStatus())));
        }
        return new Table(List.of("Order", "Placed", "Status", "Amount", "Payment"), rows);
    }
}
