package com.shifa.oms.order;

import com.shifa.oms.order.dto.DeliveryPartnerSummaryResponse;
import com.shifa.oms.order.dto.DeliveryPartnerSummaryResponse.PartnerStats;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DeliveryPartnerService}: an order is bucketed into
 * exactly one of QuikShipX / in-house / POS, and the in-transit / delivered /
 * cancelled / COD-to-collect metrics are tallied per partner.
 */
@ExtendWith(MockitoExtension.class)
class DeliveryPartnerServiceTest {

    @Mock private OrderRepository orderRepository;

    private DeliveryPartnerService service() {
        // Fixed clock so the "this month" window is deterministic.
        Clock clock = Clock.fixed(
                LocalDateTime.of(2026, 6, 15, 12, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant(),
                ZoneId.of("Asia/Kolkata"));
        return new DeliveryPartnerService(orderRepository, clock);
    }

    private OrderEntity order(DeliveryMethod method, OrderSource source, OrderStatus status,
                              String total, String outstanding) {
        OrderEntity o = new OrderEntity(
                "SHR-" + System.nanoTime(), source, 1L,
                "Cust", "9812345678", "addr", "City", "State", "411001");
        o.setDeliveryMethod(method);
        o.setOrderStatus(status);
        o.applyAmounts(new BigDecimal(total), BigDecimal.ZERO,
                new BigDecimal(outstanding), new BigDecimal(outstanding),
                com.shifa.oms.order.domain.PaymentStatus.COD);
        // The service sums customer_outstanding as "COD to collect".
        o.setCustomerOutstanding(new BigDecimal(outstanding));
        ReflectionTestUtils.setField(o, "createdAt", LocalDateTime.of(2026, 6, 10, 10, 0));
        return o;
    }

    @Test
    void partitionsOrdersIntoThePartnerBucketsAndTalliesMetrics() {
        OrderEntity courierInTransit = order(DeliveryMethod.QUIKSHIPX, OrderSource.SALESPERSON,
                OrderStatus.IN_TRANSIT, "1000.00", "1000.00");
        OrderEntity courierDelivered = order(DeliveryMethod.QUIKSHIPX, OrderSource.SALESPERSON,
                OrderStatus.DELIVERED, "500.00", "0.00");
        OrderEntity inHouseDelivered = order(DeliveryMethod.IN_HOUSE, OrderSource.SALESPERSON,
                OrderStatus.DELIVERED, "300.00", "0.00");
        OrderEntity inHouseCancelled = order(DeliveryMethod.IN_HOUSE, OrderSource.SALESPERSON,
                OrderStatus.CANCELLED, "999.00", "0.00");
        OrderEntity posSale = order(DeliveryMethod.IN_HOUSE, OrderSource.STORE,
                OrderStatus.CLOSED, "250.00", "0.00");

        when(orderRepository.findAll()).thenReturn(List.of(
                courierInTransit, courierDelivered, inHouseDelivered, inHouseCancelled, posSale));

        DeliveryPartnerSummaryResponse res = service().summary(null, null);

        // QuikShipX: 2 orders, 1 in transit + 1 delivered, revenue 1500, COD 1000.
        PartnerStats q = res.quikShipX();
        assertThat(q.orderCount()).isEqualTo(2);
        assertThat(q.inTransit()).isEqualTo(1);
        assertThat(q.delivered()).isEqualTo(1);
        assertThat(q.revenue()).isEqualByComparingTo("1500.00");
        assertThat(q.codToCollect()).isEqualByComparingTo("1000.00");

        // In-house: 2 orders (delivered + cancelled); the cancelled one is NOT revenue.
        PartnerStats h = res.inHouse();
        assertThat(h.orderCount()).isEqualTo(2);
        assertThat(h.delivered()).isEqualTo(1);
        assertThat(h.cancelled()).isEqualTo(1);
        assertThat(h.revenue()).isEqualByComparingTo("300.00");

        // POS: the single store sale (deliveryMethod IN_HOUSE but source STORE → POS).
        PartnerStats p = res.pos();
        assertThat(p.orderCount()).isEqualTo(1);
        assertThat(p.revenue()).isEqualByComparingTo("250.00");

        // Total across all three.
        assertThat(res.total().orderCount()).isEqualTo(5);
        assertThat(res.total().revenue()).isEqualByComparingTo("2050.00");
    }
}
