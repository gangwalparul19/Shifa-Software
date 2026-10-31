package com.shifa.oms.courier;

import com.shifa.oms.courier.dto.TrackByMobileResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TrackingService#shipmentFor} — the tracking-link
 * precedence that powers end-to-end tracking for an in-house parcel handed to a
 * local delivery partner (in-house delivery-partner feature, V76).
 */
class TrackingServiceTest {

    private static final long ORDER_ID = 10L;
    private static final long COMPANY_ID = 1L;

    private OrderRepository orderRepository;
    private CourierRecordRepository courierRecordRepository;
    private CourierCompanyRepository courierCompanyRepository;
    private com.shifa.oms.quikshipx.ShipmentTrackingEventRepository trackingEventRepository;
    private com.shifa.oms.settings.AppSettingsRepository appSettingsRepository;
    private TrackingService trackingService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        courierRecordRepository = mock(CourierRecordRepository.class);
        courierCompanyRepository = mock(CourierCompanyRepository.class);
        trackingEventRepository = mock(com.shifa.oms.quikshipx.ShipmentTrackingEventRepository.class);
        // Real SettingsService over a mocked repository — Mockito (Java 25 / Byte
        // Buddy) cannot mock the concrete SettingsService class directly.
        appSettingsRepository = mock(com.shifa.oms.settings.AppSettingsRepository.class);
        com.shifa.oms.settings.SettingsService settingsService =
                new com.shifa.oms.settings.SettingsService(appSettingsRepository);
        trackingService = new TrackingService(
                orderRepository, courierRecordRepository, courierCompanyRepository,
                trackingEventRepository, settingsService);
    }

    /**
     * A vendor-provided direct tracking link (stored verbatim on the record) wins
     * over the courier company's {@code {awb}} template.
     */
    @Test
    void directVendorLinkWinsOverCompanyTemplate() {
        CourierRecord record = new CourierRecord(ORDER_ID);
        record.assign(COMPANY_ID, "LR-55", null, null, "https://track.localrunner.in/LR-55");
        when(courierRecordRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(record));
        when(courierCompanyRepository.findById(COMPANY_ID))
                .thenReturn(Optional.of(new CourierCompany("Local Runner",
                        "https://generic.example/track/{awb}")));

        Optional<ShipmentInfo> shipment = trackingService.shipmentFor(ORDER_ID);

        assertThat(shipment).isPresent();
        assertThat(shipment.get().awb()).isEqualTo("LR-55");
        assertThat(shipment.get().courierName()).isEqualTo("Local Runner");
        assertThat(shipment.get().trackingUrl()).isEqualTo("https://track.localrunner.in/LR-55");
    }

    /** With no direct link, the company's {@code {awb}} template is used. */
    @Test
    void fallsBackToCompanyTemplateWhenNoDirectLink() {
        CourierRecord record = new CourierRecord(ORDER_ID);
        record.assign(COMPANY_ID, "BD123", null, null, null);
        when(courierRecordRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(record));
        when(courierCompanyRepository.findById(COMPANY_ID))
                .thenReturn(Optional.of(new CourierCompany("Blue Dart",
                        "https://bluedart.example/track/{awb}")));

        Optional<ShipmentInfo> shipment = trackingService.shipmentFor(ORDER_ID);

        assertThat(shipment).isPresent();
        assertThat(shipment.get().trackingUrl()).isEqualTo("https://bluedart.example/track/BD123");
    }

    /**
     * A vendor may give a ready-made link but no clean AWB — the order is still
     * trackable (previously a blank AWB meant "nothing to track").
     */
    @Test
    void trackableWithADirectLinkEvenWhenAwbBlank() {
        CourierRecord record = new CourierRecord(ORDER_ID);
        record.assign(COMPANY_ID, null, null, null, "https://track.localrunner.in/abc");
        when(courierRecordRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(record));
        when(courierCompanyRepository.findById(COMPANY_ID))
                .thenReturn(Optional.of(new CourierCompany("Local Runner", null)));

        Optional<ShipmentInfo> shipment = trackingService.shipmentFor(ORDER_ID);

        assertThat(shipment).isPresent();
        assertThat(shipment.get().awb()).isNull();
        assertThat(shipment.get().trackingUrl()).isEqualTo("https://track.localrunner.in/abc");
    }

    /** No AWB and no direct link = nothing to track. */
    @Test
    void notTrackableWithNeitherAwbNorLink() {
        CourierRecord record = new CourierRecord(ORDER_ID);
        record.assign(COMPANY_ID, null, null, null, null);
        when(courierRecordRepository.findByOrderId(ORDER_ID)).thenReturn(Optional.of(record));

        assertThat(trackingService.shipmentFor(ORDER_ID)).isEmpty();
    }

    @Test
    void emptyWhenNoCourierRecord() {
        when(courierRecordRepository.findByOrderId(any())).thenReturn(Optional.empty());
        assertThat(trackingService.shipmentFor(ORDER_ID)).isEmpty();
    }

    // --- ordersByMobile (public self-service tracking page) -----------------

    private static final String MOBILE = "9876543210";

    /** Privacy-safe cards, newest-first, with a product summary from the line items. */
    @Test
    void ordersByMobileReturnsPrivacySafeCardsNewestFirst() {
        OrderEntity newer = order("SHR-2", OrderStatus.OUT_FOR_DELIVERY, "tok-2",
                LocalDateTime.of(2026, 5, 2, 10, 0),
                line("Amla Juice", 2), line("Neem Caps", 1));
        OrderEntity older = order("SHR-1", OrderStatus.DELIVERED, "tok-1",
                LocalDateTime.of(2026, 5, 1, 10, 0),
                line("Honey", 1));
        // Repo contract is already DESC by created_at.
        when(orderRepository.findTop25ByCustomerMobileOrderByCreatedAtDescIdDesc(MOBILE))
                .thenReturn(List.of(newer, older));

        TrackByMobileResponse response = trackingService.ordersByMobile(" 98765-43210 ");

        assertThat(response.orders()).hasSize(2);
        TrackByMobileResponse.OrderCard first = response.orders().get(0);
        assertThat(first.orderCode()).isEqualTo("SHR-2");
        assertThat(first.trackingToken()).isEqualTo("tok-2");
        assertThat(first.productSummary()).isEqualTo("Amla Juice x2, Neem Caps x1");
        assertThat(first.statusLabel()).isEqualTo("Out for delivery");
        assertThat(first.stage()).isEqualTo("SHIPPED");
        assertThat(first.delivered()).isFalse();
        assertThat(first.placedOn()).isEqualTo(LocalDate.of(2026, 5, 2));

        TrackByMobileResponse.OrderCard second = response.orders().get(1);
        assertThat(second.orderCode()).isEqualTo("SHR-1");
        assertThat(second.delivered()).isTrue();
        assertThat(second.stage()).isEqualTo("DELIVERED");
    }

    /** The card's statusLabel/stage match the token-view's for the same order. */
    @Test
    void cardStatusLabelMatchesTokenView() {
        OrderEntity order = order("SHR-9", OrderStatus.OUT_FOR_DELIVERY, "tok-9",
                LocalDateTime.of(2026, 5, 3, 9, 0), line("Amla Juice", 1));
        when(orderRepository.findTop25ByCustomerMobileOrderByCreatedAtDescIdDesc(MOBILE))
                .thenReturn(List.of(order));
        when(orderRepository.findByTrackingToken("tok-9")).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(any())).thenReturn(Optional.empty());

        TrackByMobileResponse.OrderCard card = trackingService.ordersByMobile(MOBILE).orders().get(0);
        var tokenView = trackingService.trackByToken("tok-9");

        assertThat(card.statusLabel()).isEqualTo(tokenView.statusLabel());
        assertThat(card.stage()).isEqualTo(tokenView.stage());
        assertThat(card.delivered()).isEqualTo(tokenView.delivered());
    }

    /** The by-mobile response carries the configured brand name from settings. */
    @Test
    void ordersByMobileReturnsConfiguredBrandName() {
        com.shifa.oms.settings.AppSettings settings = new com.shifa.oms.settings.AppSettings();
        settings.setLegalName("Acme Wellness Pvt Ltd");
        when(appSettingsRepository.findById(com.shifa.oms.settings.AppSettings.SINGLETON_ID))
                .thenReturn(Optional.of(settings));
        when(orderRepository.findTop25ByCustomerMobileOrderByCreatedAtDescIdDesc(MOBILE)).thenReturn(List.of());

        assertThat(trackingService.ordersByMobile(MOBILE).brandName()).isEqualTo("Acme Wellness Pvt Ltd");
    }

    /**
     * The token view exposes the stored QuikShipX scan timeline (Option B) —
     * detailed courier events, newest first, read from {@code shipment_tracking_events}
     * with no live QuikShipX call.
     */
    @Test
    void trackByTokenIncludesStoredDetailedEvents() {
        OrderEntity order = order("SHR-7", OrderStatus.IN_TRANSIT, "tok-7",
                LocalDateTime.of(2026, 5, 4, 8, 0), line("Amla Juice", 1));
        ReflectionTestUtils.setField(order, "id", 77L);
        when(orderRepository.findByTrackingToken("tok-7")).thenReturn(Optional.of(order));
        when(courierRecordRepository.findByOrderId(any())).thenReturn(Optional.empty());
        when(trackingEventRepository.findTop100ByOrderIdOrderBySortOrderAscIdAsc(77L)).thenReturn(List.of(
                new com.shifa.oms.quikshipx.ShipmentTrackingEvent(
                        77L, "In Transit", "Indore_Dakachya_GW (Madhya Pradesh)",
                        "Vehicle Departed", "2026-10-05 01:53:10", 0),
                new com.shifa.oms.quikshipx.ShipmentTrackingEvent(
                        77L, "Manifested", "Indore_Dakachya_GW (Madhya Pradesh)",
                        "Manifest uploaded", "2026-10-03 15:15:07", 1)));

        var view = trackingService.trackByToken("tok-7");

        assertThat(view.events()).hasSize(2);
        assertThat(view.events().get(0).status()).isEqualTo("In Transit");
        assertThat(view.events().get(0).instructions()).isEqualTo("Vehicle Departed");
        assertThat(view.events().get(0).location()).isEqualTo("Indore_Dakachya_GW (Madhya Pradesh)");
        assertThat(view.events().get(0).scanAt()).isEqualTo("2026-10-05 01:53:10");
        assertThat(view.events().get(1).status()).isEqualTo("Manifested");
    }

    /** An unknown mobile yields an empty list (clean "no orders" UX), never a throw. */
    @Test
    void ordersByMobileEmptyForUnknownNumber() {
        when(orderRepository.findTop25ByCustomerMobileOrderByCreatedAtDescIdDesc(MOBILE))
                .thenReturn(List.of());
        assertThat(trackingService.ordersByMobile(MOBILE).orders()).isEmpty();
    }

    /** A malformed mobile (not exactly 10 digits) returns empty without hitting the repo. */
    @Test
    void ordersByMobileEmptyForMalformedNumber() {
        assertThat(trackingService.ordersByMobile("12345").orders()).isEmpty();
        assertThat(trackingService.ordersByMobile(null).orders()).isEmpty();
    }

    /** More than the cap of 25 orders is bounded to the 25 most recent. */
    @Test
    void ordersByMobileCapsAtTwentyFive() {
        java.util.List<OrderEntity> many = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(order("SHR-" + i, OrderStatus.DELIVERED, "tok-" + i,
                    LocalDateTime.of(2026, 5, 1, 0, 0), line("Honey", 1)));
        }
        when(orderRepository.findTop25ByCustomerMobileOrderByCreatedAtDescIdDesc(eq(MOBILE)))
                .thenReturn(many.subList(0, 25));
        assertThat(trackingService.ordersByMobile(MOBILE).orders()).hasSize(25);
    }

    private static OrderEntity order(String code, OrderStatus status, String token,
                                     LocalDateTime createdAt, OrderLineItem... lines) {
        OrderEntity order = new OrderEntity(code, OrderSource.SALESPERSON, 1L,
                "Asha Kumari", MOBILE, "1 Rd", "City", "State", "123456");
        order.setOrderStatus(status);
        for (OrderLineItem line : lines) {
            order.addLineItem(line);
        }
        ReflectionTestUtils.setField(order, "trackingToken", token);
        ReflectionTestUtils.setField(order, "createdAt", createdAt);
        return order;
    }

    private static OrderLineItem line(String name, int qty) {
        return new OrderLineItem(1L, name, qty, BigDecimal.ONE, BigDecimal.valueOf(qty));
    }
}
