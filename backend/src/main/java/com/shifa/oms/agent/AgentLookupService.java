package com.shifa.oms.agent;

import com.shifa.oms.agent.dto.AgentLookupResponse;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Live-agent order lookup by order code, mobile number, or AWB (Req 15.1, 15.2).
 *
 * <p>Returns the matched order's current status plus tracking details (AWB,
 * courier, tracking link, ETA) and its COD/payment info, or a structured
 * no-match result when nothing matches (Req 15.2). This is the query a live agent
 * runs (via the storefront) to answer a customer's "where is my order?"; it never
 * mutates state.
 */
@Service
public class AgentLookupService {

    /** The standard no-match message (Req 15.2). */
    public static final String NO_MATCH_MESSAGE = "No matching order was found.";

    private final OrderRepository orderRepository;
    private final CourierRecordRepository courierRecordRepository;
    private final CourierCompanyRepository courierCompanyRepository;

    public AgentLookupService(OrderRepository orderRepository,
                              CourierRecordRepository courierRecordRepository,
                              CourierCompanyRepository courierCompanyRepository) {
        this.orderRepository = orderRepository;
        this.courierRecordRepository = courierRecordRepository;
        this.courierCompanyRepository = courierCompanyRepository;
    }

    /** Lookup keys accepted by the {@code key}/{@code value} form. */
    public enum LookupKey {
        ORDER_CODE, MOBILE, AWB;

        static Optional<LookupKey> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "ordercode", "order_code", "order", "orderid", "order_id", "id" ->
                        Optional.of(ORDER_CODE);
                case "mobile", "phone", "customermobile", "customer_mobile" -> Optional.of(MOBILE);
                case "awb", "tracking", "trackingid", "tracking_id" -> Optional.of(AWB);
                default -> Optional.empty();
            };
        }
    }

    /**
     * Looks up an order by an explicit key and value (Req 15.1).
     *
     * @param key   one of {@code orderCode} / {@code mobile} / {@code awb}
     * @param value the value to match
     * @return the tracking details, or a no-match result (Req 15.2)
     */
    @Transactional(readOnly = true)
    public AgentLookupResponse lookup(String key, String value) {
        if (value == null || value.isBlank()) {
            return AgentLookupResponse.notFound(NO_MATCH_MESSAGE);
        }
        Optional<LookupKey> parsed = LookupKey.parse(key);
        if (parsed.isEmpty()) {
            return AgentLookupResponse.notFound(NO_MATCH_MESSAGE);
        }
        return switch (parsed.get()) {
            case ORDER_CODE -> byOrderCode(value.trim());
            case MOBILE -> byMobile(value.trim());
            case AWB -> byAwb(value.trim());
        };
    }

    /**
     * Looks up an order by whichever discrete parameter is provided, trying order
     * code, then mobile, then AWB (Req 15.1).
     *
     * @param orderCode the order code, or {@code null}
     * @param mobile    the customer mobile, or {@code null}
     * @param awb       the AWB, or {@code null}
     * @return the tracking details, or a no-match result (Req 15.2)
     */
    @Transactional(readOnly = true)
    public AgentLookupResponse lookupByAny(String orderCode, String mobile, String awb) {
        if (orderCode != null && !orderCode.isBlank()) {
            return byOrderCode(orderCode.trim());
        }
        if (mobile != null && !mobile.isBlank()) {
            return byMobile(mobile.trim());
        }
        if (awb != null && !awb.isBlank()) {
            return byAwb(awb.trim());
        }
        return AgentLookupResponse.notFound(NO_MATCH_MESSAGE);
    }

    private AgentLookupResponse byOrderCode(String orderCode) {
        return orderRepository.findByOrderCode(orderCode)
                .map(this::toResponse)
                .orElseGet(() -> AgentLookupResponse.notFound(NO_MATCH_MESSAGE));
    }

    private AgentLookupResponse byMobile(String mobile) {
        List<OrderEntity> orders = orderRepository.findByCustomerMobileOrderByCreatedAtDesc(mobile);
        if (orders.isEmpty()) {
            return AgentLookupResponse.notFound(NO_MATCH_MESSAGE);
        }
        // Most recent order for the customer.
        return toResponse(orders.get(0));
    }

    private AgentLookupResponse byAwb(String awb) {
        Optional<CourierRecord> record = courierRecordRepository.findByAwb(awb);
        if (record.isEmpty()) {
            return AgentLookupResponse.notFound(NO_MATCH_MESSAGE);
        }
        return orderRepository.findById(record.get().getOrderId())
                .map(order -> toResponse(order, record.get()))
                .orElseGet(() -> AgentLookupResponse.notFound(NO_MATCH_MESSAGE));
    }

    private AgentLookupResponse toResponse(OrderEntity order) {
        CourierRecord record = courierRecordRepository.findByOrderId(order.getId()).orElse(null);
        return toResponse(order, record);
    }

    private AgentLookupResponse toResponse(OrderEntity order, CourierRecord record) {
        String awb = null;
        String courierName = null;
        String trackingUrl = null;
        java.time.LocalDate eta = null;
        if (record != null && record.getAwb() != null && !record.getAwb().isBlank()) {
            awb = record.getAwb();
            eta = record.getEstimatedDelivery();
            if (record.getCourierCompanyId() != null) {
                Optional<CourierCompany> company =
                        courierCompanyRepository.findById(record.getCourierCompanyId());
                if (company.isPresent()) {
                    courierName = company.get().getName();
                    trackingUrl = company.get().trackingUrl(awb);
                }
            }
        }
        return new AgentLookupResponse(
                true,
                "Order found.",
                order.getOrderCode(),
                order.getOrderStatus(),
                awb,
                courierName,
                trackingUrl,
                eta,
                order.getCodAmount(),
                order.getPaymentStatus());
    }
}
