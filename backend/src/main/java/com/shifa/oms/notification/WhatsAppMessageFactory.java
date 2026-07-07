package com.shifa.oms.notification;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a fully-resolved {@link WhatsAppMessage} for a notification event from a
 * {@link NotificationContext}, using the pre-approved template and parameter
 * mapping from the {@link WhatsAppTemplateRegistry} (Req 14.1, 14.2, 14.3).
 *
 * <p>The mapping is pure, so Property 20 can assert the content rules directly:
 * <ul>
 *   <li><b>Dispatched</b>: order id, courier company, AWB, tracking link,
 *       estimated delivery, and — only when the order is COD or Partially_Paid —
 *       the COD amount (Req 14.1);</li>
 *   <li><b>Out_For_Delivery / Delivered / RTO / Courier_Lost</b>: order id plus a
 *       status label reflecting the new state (Req 14.2).</li>
 * </ul>
 * Every message it returns references a template registered in the registry
 * (Req 14.3).
 */
@Component
public class WhatsAppMessageFactory {

    private final WhatsAppTemplateRegistry registry;

    public WhatsAppMessageFactory(WhatsAppTemplateRegistry registry) {
        this.registry = registry;
    }

    /**
     * Builds the WhatsApp message for an event and context.
     *
     * @param event   the lifecycle event
     * @param context the order/tracking facts
     * @return the resolved, sendable message
     */
    public WhatsAppMessage build(NotificationEvent event, NotificationContext context) {
        WhatsAppTemplate template = registry.templateFor(event);
        List<WhatsAppMessage.Param> params = switch (event) {
            case ORDER_CONFIRMED -> confirmationParams(context);
            case DISPATCHED -> dispatchParams(context);
            default -> statusParams(event, context);
        };
        return new WhatsAppMessage(context.customerMobile(), template.templateName(), params);
    }

    /**
     * Order-confirmation params: the customer name (greeting), the order id, and
     * the store/brand name (ROADMAP 1.2). Sent once when the order is placed.
     */
    private List<WhatsAppMessage.Param> confirmationParams(NotificationContext ctx) {
        List<WhatsAppMessage.Param> params = new ArrayList<>();
        params.add(param(WhatsAppTemplateRegistry.PARAM_CUSTOMER_NAME, ctx.customerName()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_ORDER_ID, ctx.orderCode()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_STORE_NAME, ctx.storeName()));
        return params;
    }

    private List<WhatsAppMessage.Param> dispatchParams(NotificationContext ctx) {
        List<WhatsAppMessage.Param> params = new ArrayList<>();
        params.add(param(WhatsAppTemplateRegistry.PARAM_ORDER_ID, ctx.orderCode()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_COURIER_COMPANY, ctx.courierName()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_AWB, ctx.awb()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_TRACKING_LINK, ctx.trackingUrl()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_ESTIMATED_DELIVERY, formatDate(ctx.estimatedDelivery())));
        // COD amount only when COD or Partially_Paid (Req 14.1).
        if (ctx.isCodApplicable()) {
            params.add(param(WhatsAppTemplateRegistry.PARAM_COD_AMOUNT, formatAmount(ctx.codAmount())));
        }
        return params;
    }

    private List<WhatsAppMessage.Param> statusParams(NotificationEvent event, NotificationContext ctx) {
        List<WhatsAppMessage.Param> params = new ArrayList<>();
        params.add(param(WhatsAppTemplateRegistry.PARAM_ORDER_ID, ctx.orderCode()));
        params.add(param(WhatsAppTemplateRegistry.PARAM_ORDER_STATUS, statusLabel(event)));
        return params;
    }

    /** Human-readable status label reflecting the new state (Req 14.2). */
    private String statusLabel(NotificationEvent event) {
        return switch (event) {
            case OUT_FOR_DELIVERY -> "Out for delivery";
            case DELIVERED -> "Delivered";
            case RTO -> "Returned to origin";
            case COURIER_LOST -> "Lost in transit";
            case DISPATCHED -> "Dispatched";
            case ORDER_CONFIRMED -> "Order confirmed";
        };
    }

    private WhatsAppMessage.Param param(String name, String value) {
        return new WhatsAppMessage.Param(name, value == null ? "" : value);
    }

    private String formatDate(LocalDate date) {
        return date == null ? "" : date.toString();
    }

    private String formatAmount(BigDecimal amount) {
        return amount == null ? "" : amount.toPlainString();
    }
}
