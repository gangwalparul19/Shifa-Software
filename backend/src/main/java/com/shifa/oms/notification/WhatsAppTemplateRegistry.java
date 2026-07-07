package com.shifa.oms.notification;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registry of pre-approved Meta WhatsApp templates, mapping each
 * {@link NotificationEvent} to its template name and ordered parameter mapping
 * (Req 14.3).
 *
 * <p>This is the single source of truth for which templates exist and what
 * parameters they carry. The {@link WhatsAppMessageFactory} consults it to build
 * every message, and {@link #isRegistered(String)} lets callers/tests assert
 * that a message references a genuinely registered (pre-approved) template.
 *
 * <p>Parameter keys used by the templates:
 * <ul>
 *   <li>{@code order_id} — the order identifier (all events);</li>
 *   <li>{@code courier_company}, {@code awb}, {@code tracking_link},
 *       {@code estimated_delivery} — dispatch tracking details (Req 14.1);</li>
 *   <li>{@code cod_amount} — included on dispatch only when the order is COD or
 *       Partially_Paid (Req 14.1);</li>
 *   <li>{@code order_status} — the new status label for status-update messages
 *       (Req 14.2).</li>
 * </ul>
 */
@Component
public class WhatsAppTemplateRegistry {

    /** Parameter key: the order identifier. */
    public static final String PARAM_ORDER_ID = "order_id";
    /** Parameter key: the courier company name. */
    public static final String PARAM_COURIER_COMPANY = "courier_company";
    /** Parameter key: the AWB number. */
    public static final String PARAM_AWB = "awb";
    /** Parameter key: the courier tracking link. */
    public static final String PARAM_TRACKING_LINK = "tracking_link";
    /** Parameter key: the estimated delivery date. */
    public static final String PARAM_ESTIMATED_DELIVERY = "estimated_delivery";
    /** Parameter key: the COD amount to collect (dispatch, when COD/Partially_Paid). */
    public static final String PARAM_COD_AMOUNT = "cod_amount";
    /** Parameter key: the new order status label. */
    public static final String PARAM_ORDER_STATUS = "order_status";
    /** Parameter key: the customer's name (order-confirmation greeting). */
    public static final String PARAM_CUSTOMER_NAME = "customer_name";
    /** Parameter key: the store/brand name (order-confirmation message). */
    public static final String PARAM_STORE_NAME = "store_name";

    private final Map<NotificationEvent, WhatsAppTemplate> templates;
    private final Set<String> registeredNames;

    public WhatsAppTemplateRegistry() {
        this.templates = buildTemplates();
        this.registeredNames = templates.values().stream()
                .map(WhatsAppTemplate::templateName)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Map<NotificationEvent, WhatsAppTemplate> buildTemplates() {
        Map<NotificationEvent, WhatsAppTemplate> map = new EnumMap<>(NotificationEvent.class);

        // Order confirmation: sent once when a storefront order is placed, greeting
        // the customer by name and acknowledging the order + store (ROADMAP 1.2).
        map.put(NotificationEvent.ORDER_CONFIRMED, new WhatsAppTemplate(
                NotificationEvent.ORDER_CONFIRMED, "order_confirmed",
                List.of(PARAM_CUSTOMER_NAME, PARAM_ORDER_ID, PARAM_STORE_NAME)));

        // Dispatch: full tracking payload; cod_amount is a declared parameter but
        // is only populated by the factory when the order is COD/Partially_Paid (Req 14.1).
        map.put(NotificationEvent.DISPATCHED, new WhatsAppTemplate(
                NotificationEvent.DISPATCHED, "order_dispatched",
                List.of(PARAM_ORDER_ID, PARAM_COURIER_COMPANY, PARAM_AWB,
                        PARAM_TRACKING_LINK, PARAM_ESTIMATED_DELIVERY, PARAM_COD_AMOUNT)));

        // Status updates: order id + the new status label (Req 14.2).
        map.put(NotificationEvent.OUT_FOR_DELIVERY, new WhatsAppTemplate(
                NotificationEvent.OUT_FOR_DELIVERY, "order_out_for_delivery",
                List.of(PARAM_ORDER_ID, PARAM_ORDER_STATUS)));
        map.put(NotificationEvent.DELIVERED, new WhatsAppTemplate(
                NotificationEvent.DELIVERED, "order_delivered",
                List.of(PARAM_ORDER_ID, PARAM_ORDER_STATUS)));
        map.put(NotificationEvent.RTO, new WhatsAppTemplate(
                NotificationEvent.RTO, "order_rto",
                List.of(PARAM_ORDER_ID, PARAM_ORDER_STATUS)));
        map.put(NotificationEvent.COURIER_LOST, new WhatsAppTemplate(
                NotificationEvent.COURIER_LOST, "order_courier_lost",
                List.of(PARAM_ORDER_ID, PARAM_ORDER_STATUS)));

        return map;
    }

    /**
     * The registered template for an event.
     *
     * @param event the notification event
     * @return the template, never {@code null} for the defined events
     * @throws IllegalArgumentException if no template is registered for the event
     */
    public WhatsAppTemplate templateFor(NotificationEvent event) {
        WhatsAppTemplate template = templates.get(event);
        if (template == null) {
            throw new IllegalArgumentException("No registered WhatsApp template for event " + event);
        }
        return template;
    }

    /** Whether a template name is registered (i.e. pre-approved for sending). */
    public boolean isRegistered(String templateName) {
        return templateName != null && registeredNames.contains(templateName);
    }

    /** All registered template names (defensive copy). */
    public Set<String> registeredTemplateNames() {
        return Set.copyOf(registeredNames);
    }

    /** Look up the template for an event without throwing. */
    public Optional<WhatsAppTemplate> find(NotificationEvent event) {
        return Optional.ofNullable(templates.get(event));
    }
}
