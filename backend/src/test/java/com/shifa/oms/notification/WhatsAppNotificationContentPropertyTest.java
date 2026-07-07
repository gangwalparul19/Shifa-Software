package com.shifa.oms.notification;

import com.shifa.oms.order.domain.PaymentStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for WhatsApp notification content and template use.
 *
 * Feature: shifa-herbal-remedies, Property 20: WhatsApp notification content and
 * template use. For any order reaching Dispatched, the outgoing message
 * parameters include the order id, courier name, AWB, tracking link, ETA, and the
 * COD_Amount if and only if the order is COD or Partially_Paid; for
 * Out_For_Delivery / Delivered / RTO / Courier_Lost the message reflects that
 * status; and every message references a registered pre-approved template.
 *
 * **Validates: Requirements 14.1, 14.2, 14.3**
 *
 * <p>Tested at the message-model / registry level via {@link WhatsAppMessageFactory}
 * — no real Meta API is called. Each property runs the jqwik default of 1000
 * tries (≥ 100).
 */
class WhatsAppNotificationContentPropertyTest {

    private final WhatsAppTemplateRegistry registry = new WhatsAppTemplateRegistry();
    private final WhatsAppMessageFactory factory = new WhatsAppMessageFactory(registry);

    // Feature: shifa-herbal-remedies, Property 20: WhatsApp notification content and template use
    // **Validates: Requirements 14.1, 14.3**
    @Property
    void dispatchMessageCarriesTrackingDetailsAndCodIffCodOrPartiallyPaid(
            @ForAll("dispatchContexts") NotificationContext context) {

        WhatsAppMessage message = factory.build(NotificationEvent.DISPATCHED, context);

        // Every message references a registered pre-approved template (Req 14.3).
        assertThat(registry.isRegistered(message.templateName())).isTrue();
        assertThat(message.recipientMobile()).isEqualTo(context.customerMobile());

        // Dispatch carries the full tracking payload (Req 14.1).
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_ORDER_ID))
                .contains(context.orderCode());
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_COURIER_COMPANY))
                .contains(context.courierName());
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_AWB))
                .contains(context.awb());
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_TRACKING_LINK))
                .contains(context.trackingUrl());
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_ESTIMATED_DELIVERY))
                .contains(context.estimatedDelivery().toString());

        // COD_Amount is present iff the order is COD or Partially_Paid (Req 14.1).
        boolean codApplicable = context.paymentStatus() == PaymentStatus.COD
                || context.paymentStatus() == PaymentStatus.PARTIALLY_PAID;
        assertThat(message.hasParameter(WhatsAppTemplateRegistry.PARAM_COD_AMOUNT))
                .isEqualTo(codApplicable);
        if (codApplicable) {
            assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_COD_AMOUNT))
                    .contains(context.codAmount().toPlainString());
        }
    }

    // Feature: shifa-herbal-remedies, Property 20: WhatsApp notification content and template use
    // **Validates: Requirements 14.2, 14.3**
    @Property
    void statusUpdateMessageReflectsStatusAndUsesRegisteredTemplate(
            @ForAll("statusEvents") NotificationEvent event,
            @ForAll("statusContexts") NotificationContext context) {

        WhatsAppMessage message = factory.build(event, context);

        // Every message references a registered pre-approved template (Req 14.3).
        assertThat(registry.isRegistered(message.templateName())).isTrue();
        // The template chosen is exactly the one the registry maps this event to.
        assertThat(message.templateName())
                .isEqualTo(registry.templateFor(event).templateName());

        // The message carries the order id and a non-blank status label (Req 14.2).
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_ORDER_ID))
                .contains(context.orderCode());
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_ORDER_STATUS))
                .isPresent();
        assertThat(message.parameterValue(WhatsAppTemplateRegistry.PARAM_ORDER_STATUS).orElseThrow())
                .isNotBlank();
        // Distinct events yield distinct templates, so the message is status-specific.
        assertThat(message.templateName()).isNotEqualTo(
                registry.templateFor(NotificationEvent.DISPATCHED).templateName());
    }

    @Provide
    Arbitrary<NotificationContext> dispatchContexts() {
        Arbitrary<String> orderCodes = Arbitraries.strings()
                .withCharRange('A', 'Z').ofMinLength(3).ofMaxLength(8).map(s -> "SHR-" + s);
        Arbitrary<String> mobiles = Arbitraries.strings().numeric().ofLength(10);
        Arbitrary<String> couriers = Arbitraries.of("Shifa Express", "BlueDart", "Delhivery", "DTDC");
        Arbitrary<String> awbs = Arbitraries.strings()
                .withCharRange('0', '9').ofLength(10).map(s -> "AWB" + s);
        Arbitrary<PaymentStatus> statuses = Arbitraries.of(PaymentStatus.values());
        Arbitrary<BigDecimal> cods = Arbitraries.integers().between(0, 100_000)
                .map(i -> new BigDecimal(i).setScale(2));
        Arbitrary<Integer> etaDays = Arbitraries.integers().between(1, 10);

        return Combinators.combine(orderCodes, mobiles, couriers, awbs, statuses, cods, etaDays)
                .as((code, mobile, courier, awb, status, cod, days) -> new NotificationContext(
                        code, mobile, courier, awb,
                        "https://track.example.com/" + awb,
                        LocalDate.of(2025, 1, 1).plusDays(days),
                        status, cod));
    }

    @Provide
    Arbitrary<NotificationContext> statusContexts() {
        Arbitrary<String> orderCodes = Arbitraries.strings()
                .withCharRange('A', 'Z').ofMinLength(3).ofMaxLength(8).map(s -> "SHR-" + s);
        Arbitrary<String> mobiles = Arbitraries.strings().numeric().ofLength(10);
        Arbitrary<PaymentStatus> statuses = Arbitraries.of(PaymentStatus.values());
        Arbitrary<BigDecimal> cods = Arbitraries.integers().between(0, 100_000)
                .map(i -> new BigDecimal(i).setScale(2));

        return Combinators.combine(orderCodes, mobiles, statuses, cods)
                .as((code, mobile, status, cod) -> new NotificationContext(
                        code, mobile, null, null, null, null, status, cod));
    }

    @Provide
    Arbitrary<NotificationEvent> statusEvents() {
        return Arbitraries.of(
                NotificationEvent.OUT_FOR_DELIVERY,
                NotificationEvent.DELIVERED,
                NotificationEvent.RTO,
                NotificationEvent.COURIER_LOST);
    }
}
