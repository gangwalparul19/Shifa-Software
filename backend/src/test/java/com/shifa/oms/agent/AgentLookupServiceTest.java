package com.shifa.oms.agent;

import com.shifa.oms.agent.dto.AgentLookupResponse;
import com.shifa.oms.courier.CourierCompany;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecord;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the live-agent order lookup (Req 15.1, 15.2), focused on the
 * no-match result (Req 15.2) and a successful lookup that returns status +
 * tracking details.
 */
class AgentLookupServiceTest {

    private OrderRepository orderRepository;
    private CourierRecordRepository courierRecordRepository;
    private CourierCompanyRepository courierCompanyRepository;
    private AgentLookupService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        courierRecordRepository = mock(CourierRecordRepository.class);
        courierCompanyRepository = mock(CourierCompanyRepository.class);
        service = new AgentLookupService(
                orderRepository, courierRecordRepository, courierCompanyRepository);
    }

    @Test
    void noMatchingOrderByOrderCodeReturnsNoMatchResult() {
        when(orderRepository.findByOrderCode("SHR-NOPE")).thenReturn(Optional.empty());

        AgentLookupResponse response = service.lookupByAny("SHR-NOPE", null, null);

        assertThat(response.found()).isFalse();
        assertThat(response.message()).isEqualTo(AgentLookupService.NO_MATCH_MESSAGE);
        assertThat(response.orderStatus()).isNull();
        assertThat(response.awb()).isNull();
    }

    @Test
    void noMatchingOrderByMobileReturnsNoMatchResult() {
        when(orderRepository.findByCustomerMobileOrderByCreatedAtDesc("9999999999"))
                .thenReturn(List.of());

        AgentLookupResponse response = service.lookup("mobile", "9999999999");

        assertThat(response.found()).isFalse();
        assertThat(response.message()).isEqualTo(AgentLookupService.NO_MATCH_MESSAGE);
    }

    @Test
    void noMatchingOrderByAwbReturnsNoMatchResult() {
        when(courierRecordRepository.findByAwb("AWB-UNKNOWN")).thenReturn(Optional.empty());

        AgentLookupResponse response = service.lookup("awb", "AWB-UNKNOWN");

        assertThat(response.found()).isFalse();
        assertThat(response.message()).isEqualTo(AgentLookupService.NO_MATCH_MESSAGE);
    }

    @Test
    void unknownKeyReturnsNoMatchResult() {
        AgentLookupResponse response = service.lookup("nonsense", "value");

        assertThat(response.found()).isFalse();
        assertThat(response.message()).isEqualTo(AgentLookupService.NO_MATCH_MESSAGE);
    }

    @Test
    void blankValueReturnsNoMatchResult() {
        AgentLookupResponse response = service.lookupByAny(null, null, null);

        assertThat(response.found()).isFalse();
        assertThat(response.message()).isEqualTo(AgentLookupService.NO_MATCH_MESSAGE);
    }

    @Test
    void foundOrderByOrderCodeReturnsStatusAndTrackingDetails() {
        OrderEntity order = order();
        when(orderRepository.findByOrderCode("SHR-000777")).thenReturn(Optional.of(order));

        CourierRecord record = new CourierRecord(order.getId());
        record.assign(1L, "AWB-XYZ", "labels/shipping/x.pdf", LocalDate.of(2025, 3, 1));
        when(courierRecordRepository.findByOrderId(any())).thenReturn(Optional.of(record));
        when(courierCompanyRepository.findById(1L)).thenReturn(Optional.of(
                new CourierCompany("Shifa Express", "https://track.example.com/{awb}")));

        AgentLookupResponse response = service.lookupByAny("SHR-000777", null, null);

        assertThat(response.found()).isTrue();
        assertThat(response.orderCode()).isEqualTo("SHR-000777");
        assertThat(response.orderStatus()).isEqualTo(OrderStatus.DISPATCHED);
        assertThat(response.awb()).isEqualTo("AWB-XYZ");
        assertThat(response.courierName()).isEqualTo("Shifa Express");
        assertThat(response.trackingUrl()).isEqualTo("https://track.example.com/AWB-XYZ");
        assertThat(response.estimatedDelivery()).isEqualTo(LocalDate.of(2025, 3, 1));
        assertThat(response.codAmount()).isEqualByComparingTo("240.00");
        assertThat(response.paymentStatus()).isEqualTo(PaymentStatus.COD);
    }

    private OrderEntity order() {
        OrderEntity order = new OrderEntity(
                "SHR-000777", OrderSource.STOREFRONT, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        order.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO.setScale(2),
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        order.setOrderStatus(OrderStatus.DISPATCHED);
        return order;
    }
}
