package com.shifa.oms.notification;

import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.quikshipx.QuikShipXModels;
import com.shifa.oms.settings.AppSettings;
import com.shifa.oms.settings.AppSettingsRepository;
import com.shifa.oms.settings.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ShipmentMilestoneNotifier} — the customer WhatsApp
 * shipment-milestone notifier (order placed / picked up / first vehicle-departed
 * / out for delivery), COD-gated and de-duplicated per order.
 *
 * <p>Java 25 + Byte Buddy cannot mock the concrete {@code WhatsAppNotificationPublisher}
 * / {@code SettingsService}, so this uses a recording subclass of the publisher
 * and a real {@code SettingsService} over a mocked repository (the established
 * pattern in this codebase).
 */
class ShipmentMilestoneNotifierTest {

    /** Captures the events the notifier enqueues, without touching the real outbox. */
    private static final class RecordingPublisher extends WhatsAppNotificationPublisher {
        final List<NotificationEvent> events = new ArrayList<>();

        RecordingPublisher() {
            super(null, null);
        }

        @Override
        public WhatsAppMessage enqueue(Long orderId, NotificationEvent event,
                                       NotificationContext context, Long salespersonUserId) {
            events.add(event);
            return new WhatsAppMessage(context.customerMobile(), event.name(), List.of());
        }
    }

    private RecordingPublisher publisher;
    private OrderMilestoneNotificationRepository markerRepository;
    private ShipmentMilestoneNotifier notifier;

    @BeforeEach
    void setUp() {
        publisher = new RecordingPublisher();
        markerRepository = mock(OrderMilestoneNotificationRepository.class);
        // No marker exists yet by default (each milestone is fresh).
        lenient().when(markerRepository.existsByOrderIdAndMilestone(anyLong(), any())).thenReturn(false);
        lenient().when(markerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        AppSettingsRepository settingsRepo = mock(AppSettingsRepository.class);
        AppSettings settings = new AppSettings();
        settings.setLegalName("Shifa Herbal Remedies");
        lenient().when(settingsRepo.findById(AppSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        SettingsService settingsService = new SettingsService(settingsRepo);

        notifier = new ShipmentMilestoneNotifier(publisher, markerRepository, settingsService);
    }

    private static OrderEntity codOrder() {
        OrderEntity order = new OrderEntity("SHR-COD-1", OrderSource.SALESPERSON, 1L,
                "Asha Kumari", "9876543210", "1 Rd", "City", "State", "123456");
        // total 499, received 0, remaining 499, COD 499 → payable on delivery.
        order.applyAmounts(new BigDecimal("499.00"), BigDecimal.ZERO, new BigDecimal("499.00"),
                new BigDecimal("499.00"), PaymentStatus.COD);
        ReflectionTestUtils.setField(order, "id", 50L);
        return order;
    }

    private static OrderEntity prepaidOrder() {
        OrderEntity order = new OrderEntity("SHR-PP-1", OrderSource.SALESPERSON, 1L,
                "Asha Kumari", "9876543210", "1 Rd", "City", "State", "123456");
        order.applyAmounts(new BigDecimal("499.00"), new BigDecimal("499.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, PaymentStatus.FULLY_PAID);
        ReflectionTestUtils.setField(order, "id", 51L);
        return order;
    }

    private static QuikShipXModels.Scan scan(String instructions) {
        return new QuikShipXModels.Scan("In Transit", "Indore_Dakachya_GW (Madhya Pradesh)",
                instructions, "2026-10-05 01:53:10");
    }

    @Test
    void orderPlacedFiresOrderConfirmedForCod() {
        notifier.orderPlaced(codOrder());
        assertThat(publisher.events).containsExactly(NotificationEvent.ORDER_CONFIRMED);
    }

    @Test
    void orderPlacedSkippedForPrepaidOrder() {
        notifier.orderPlaced(prepaidOrder());
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void onScansFiresPickedUpDepartedAndOutForDelivery() {
        List<QuikShipXModels.Scan> scans = List.of(
                scan("Out for delivery"),
                scan("Vehicle Departed"),
                scan("Vehicle Departed"),
                scan("Shipment picked up"),
                scan("Manifest uploaded"));

        notifier.onScans(codOrder(), scans);

        // One each — the two "Vehicle Departed" scans collapse to a single SHIPMENT_DEPARTED.
        assertThat(publisher.events).containsExactlyInAnyOrder(
                NotificationEvent.PICKED_UP,
                NotificationEvent.SHIPMENT_DEPARTED,
                NotificationEvent.OUT_FOR_DELIVERY);
    }

    @Test
    void alreadyNotifiedMilestoneIsNotResent() {
        // The marker for SHIPMENT_DEPARTED already exists → skip it, fire the others.
        when(markerRepository.existsByOrderIdAndMilestone(eq(50L),
                eq(ShipmentMilestoneNotifier.M_SHIPMENT_DEPARTED))).thenReturn(true);

        notifier.onScans(codOrder(), List.of(scan("Vehicle Departed"), scan("Shipment picked up")));

        assertThat(publisher.events).containsExactly(NotificationEvent.PICKED_UP);
    }

    @Test
    void onScansSkippedForPrepaidOrder() {
        notifier.onScans(prepaidOrder(), List.of(scan("Out for delivery")));
        assertThat(publisher.events).isEmpty();
    }

    @Test
    void onScansNoMilestoneScansFiresNothing() {
        notifier.onScans(codOrder(), List.of(scan("Manifest uploaded"), scan("Bag Added To Trip")));
        assertThat(publisher.events).isEmpty();
    }
}
