package com.shifa.oms.dashboard;

import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse;
import com.shifa.oms.dashboard.dto.ChannelDashboardResponse.StageCount;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderLineItem;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.quikshipx.OrderShipment;
import com.shifa.oms.quikshipx.OrderShipmentRepository;
import com.shifa.oms.reconciliation.ReceivableRepository;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelDashboardService}: Portal vs Shopify vs All split,
 * revenue excluding rejected/cancelled, previous-period change, channel scoping of
 * every section, a pipeline that adds up to the order count, Shopify label/pickup
 * queues, and keeping Shopify prepaid money separate from team collections.
 */
class ChannelDashboardServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    /** "Today" = 15 Jun 2026. */
    private static final Clock CLOCK = Clock.fixed(
            LocalDate.of(2026, 6, 15).atStartOfDay(ZONE).plusHours(12).toInstant(), ZONE);
    private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
    private static final LocalDate TO = LocalDate.of(2026, 6, 30);

    private OrderRepository orderRepository;
    private OrderShipmentRepository shipmentRepository;
    private ChannelDashboardService service;
    private final List<OrderEntity> orders = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        shipmentRepository = mock(OrderShipmentRepository.class);
        service = new ChannelDashboardService(orderRepository, mock(ReceivableRepository.class),
                mock(UserRepository.class), shipmentRepository, CLOCK);
        when(orderRepository.findAll()).thenReturn(orders);
    }

    private ChannelDashboardResponse run(DashboardChannel channel) {
        return service.dashboard(channel, MetricsPeriod.CUSTOM, SalesBucket.DAY, FROM, TO);
    }

    @Test
    void splitsChannelsAndExcludesRejectedAndCancelledFromRevenue() {
        add(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "1000", "0", PaymentStatus.FULLY_PAID, 6, 10);
        add(OrderSource.SALESPERSON, OrderStatus.REJECTED, "999", "0", PaymentStatus.COD, 6, 11);
        add(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "2000", "0", PaymentStatus.FULLY_PAID, 6, 12);
        add(OrderSource.SHOPIFY, OrderStatus.CANCELLED, "500", "0", PaymentStatus.COD, 6, 12);

        ChannelDashboardResponse r = run(DashboardChannel.ALL);

        assertThat(r.split().all().orders()).isEqualTo(4);
        assertThat(r.split().all().revenue()).isEqualByComparingTo("3000");
        assertThat(r.split().portal().orders()).isEqualTo(2);
        assertThat(r.split().portal().revenue()).isEqualByComparingTo("1000");
        assertThat(r.split().shopify().revenue()).isEqualByComparingTo("2000");
        assertThat(r.split().shopify().revenueSharePct()).isEqualByComparingTo("66.7");
        assertThat(r.kpis().cancelledRejected()).isEqualTo(2);
    }

    @Test
    void comparesAgainstThePreviousPeriod() {
        add(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "1500", "0", PaymentStatus.FULLY_PAID, 6, 10);
        add(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "1000", "0", PaymentStatus.FULLY_PAID, 5, 20); // previous

        ChannelDashboardResponse r = run(DashboardChannel.ALL);

        assertThat(r.kpis().previousRevenue()).isEqualByComparingTo("1000");
        assertThat(r.kpis().revenueChangePct()).isEqualByComparingTo("50.0");
    }

    @Test
    void shopifyChannelScopesEverySectionAndHidesPortalOnlyParts() {
        add(OrderSource.SALESPERSON, OrderStatus.PENDING_ADMIN_APPROVAL, "700", "700", PaymentStatus.COD, 6, 10);
        add(OrderSource.SHOPIFY, OrderStatus.DELIVERED, "1200", "0", PaymentStatus.FULLY_PAID, 6, 10);

        ChannelDashboardResponse r = run(DashboardChannel.SHOPIFY);

        assertThat(r.kpis().orders()).isEqualTo(1);
        assertThat(r.kpis().revenue()).isEqualByComparingTo("1200");
        assertThat(r.kpis().codToCollect()).isEqualByComparingTo("0");
        assertThat(r.queues().portal()).isNull();
        assertThat(r.queues().shopify()).isNotNull();
        assertThat(r.performance().topSalespeople()).isEmpty();
        assertThat(r.trend()).allSatisfy(p -> assertThat(p.portal()).isEqualByComparingTo("0"));
        // The split still shows both channels for comparison.
        assertThat(r.split().portal().orders()).isEqualTo(1);
    }

    @Test
    void pipelineListsEveryStageAndAddsUpToTheOrderCount() {
        add(OrderSource.SALESPERSON, OrderStatus.PENDING_ADMIN_APPROVAL, "100", "100", PaymentStatus.COD, 6, 2);
        add(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "100", "0", PaymentStatus.FULLY_PAID, 6, 3);
        add(OrderSource.SHOPIFY, OrderStatus.RTO, "100", "0", PaymentStatus.FULLY_PAID, 6, 4);
        add(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "100", "0", PaymentStatus.FULLY_PAID, 6, 5);

        ChannelDashboardResponse r = run(DashboardChannel.ALL);

        assertThat(r.pipeline()).hasSize(7);
        assertThat(r.pipeline().stream().mapToLong(StageCount::count).sum()).isEqualTo(r.kpis().orders());
        // 1 delivered vs 1 returned => 50% delivery success.
        assertThat(r.kpis().deliverySuccessPct()).isEqualByComparingTo("50.0");
    }

    @Test
    void shopifyQueuesSplitLabelsToPrintFromAwaitingPickupAndCountStuck() {
        OrderEntity printed = add(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "100", "0", PaymentStatus.FULLY_PAID, 6, 3);
        add(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "100", "0", PaymentStatus.FULLY_PAID, 6, 3);
        add(OrderSource.SHOPIFY, OrderStatus.LABEL_GENERATED, "100", "0", PaymentStatus.FULLY_PAID, 6, 3);
        OrderShipment s1 = new OrderShipment(printed.getId(), printed.getOrderCode());
        s1.markLabelPrinted(LocalDateTime.of(2026, 6, 14, 9, 0));
        when(shipmentRepository.findByOrderIdIn(any())).thenReturn(List.of(s1));

        ChannelDashboardResponse r = run(DashboardChannel.ALL);

        assertThat(r.queues().shopify().stuck()).isEqualTo(1);
        assertThat(r.queues().shopify().labelsToPrint()).isEqualTo(1);
        assertThat(r.queues().shopify().awaitingPickup()).isEqualTo(1);
    }

    @Test
    void keepsShopifyPrepaidSeparateFromTeamCollections() {
        add(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "1000", "400", PaymentStatus.PARTIALLY_PAID, 6, 15);
        add(OrderSource.SHOPIFY, OrderStatus.COURIER_ASSIGNED, "2000", "0", PaymentStatus.FULLY_PAID, 6, 15);

        ChannelDashboardResponse r = run(DashboardChannel.ALL);

        assertThat(r.payments().collectedByTeam()).isEqualByComparingTo("600");
        assertThat(r.payments().paidOnShopify()).isEqualByComparingTo("2000");
        assertThat(r.payments().todayOrders()).isEqualTo(2);
        assertThat(r.payments().todayCollectedByTeam()).isEqualByComparingTo("600");
        assertThat(r.payments().todayPaidOnShopify()).isEqualByComparingTo("2000");
        assertThat(r.payments().codToCollect()).isEqualByComparingTo("400");
    }

    @Test
    void topProductsAndStatesRankByRevenue() {
        OrderEntity a = add(OrderSource.SHOPIFY, OrderStatus.DELIVERED, "999", "0", PaymentStatus.FULLY_PAID, 6, 5);
        a.addLineItem(new OrderLineItem(null, "Power Gold", 1, new BigDecimal("999"), new BigDecimal("999")));
        OrderEntity b = add(OrderSource.SALESPERSON, OrderStatus.DELIVERED, "2200", "0", PaymentStatus.FULLY_PAID, 6, 6);
        b.addLineItem(new OrderLineItem(null, "Height Heal", 2, new BigDecimal("1100"), new BigDecimal("2200")));

        ChannelDashboardResponse r = run(DashboardChannel.ALL);

        assertThat(r.performance().topProducts().get(0).name()).isEqualTo("Height Heal");
        assertThat(r.performance().topProducts().get(0).count()).isEqualTo(2);
        assertThat(r.performance().topStates().get(0).revenue()).isEqualByComparingTo("3199");
    }

    private OrderEntity add(OrderSource source, OrderStatus status, String total, String outstanding,
                            PaymentStatus payment, int month, int day) {
        OrderEntity o = new OrderEntity("SHR-T" + nextId, source, null,
                "Asha", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        BigDecimal t = new BigDecimal(total);
        BigDecimal cod = new BigDecimal(outstanding);
        o.applyAmounts(t, t.subtract(cod), cod, cod, payment);
        o.setCustomerOutstanding(cod);
        o.setOrderStatus(status);
        ReflectionTestUtils.setField(o, "id", nextId++);
        ReflectionTestUtils.setField(o, "createdAt", LocalDateTime.of(2026, month, day, 10, 0));
        orders.add(o);
        return o;
    }
}
