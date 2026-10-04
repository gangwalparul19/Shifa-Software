package com.shifa.oms.courier;

import com.shifa.oms.order.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    private TrackingService trackingService;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        courierRecordRepository = mock(CourierRecordRepository.class);
        courierCompanyRepository = mock(CourierCompanyRepository.class);
        trackingService = new TrackingService(
                orderRepository, courierRecordRepository, courierCompanyRepository);
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
}
