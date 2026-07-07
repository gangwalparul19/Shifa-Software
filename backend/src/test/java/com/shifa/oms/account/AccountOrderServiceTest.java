package com.shifa.oms.account;

import com.shifa.oms.account.dto.AccountOrderSummary;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.courier.CourierCompanyRepository;
import com.shifa.oms.courier.CourierRecordRepository;
import com.shifa.oms.courier.TrackingService;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.order.dto.OrderResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccountOrderService} with mocked repositories (no DB).
 * Verifies the association rule (history matched by user id OR the account's
 * mobile) and that a customer cannot fetch an order that is not theirs.
 */
@ExtendWith(MockitoExtension.class)
class AccountOrderServiceTest {

    private static final Long ALICE = 1L;

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CourierRecordRepository courierRecordRepository;
    @Mock
    private CourierCompanyRepository courierCompanyRepository;

    private AccountOrderService service;

    @BeforeEach
    void setUp() {
        // TrackingService is a concrete class; build a real one from mocked repos
        // (mirrors OrderServiceTest). With no courier record, shipmentFor() is empty.
        TrackingService trackingService = new TrackingService(
                orderRepository, courierRecordRepository, courierCompanyRepository);
        service = new AccountOrderService(orderRepository, userRepository, trackingService);
        lenient().when(courierRecordRepository.findByOrderId(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(Optional.empty());
    }

    private User customer(String mobile) {
        User u = new User("alice", "hash", Role.CUSTOMER, "Alice", null, mobile, true);
        ReflectionTestUtils.setField(u, "id", ALICE);
        return u;
    }

    private OrderEntity order(long id, String code, String mobile, Long customerUserId) {
        OrderEntity o = new OrderEntity(code, OrderSource.STOREFRONT, null,
                "Alice", mobile, "1 St", "City", "State", "411001");
        o.setCustomerUserId(customerUserId);
        o.applyAmounts(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("100.00"),
                new BigDecimal("100.00"), PaymentStatus.COD);
        o.setOrderStatus(OrderStatus.PENDING_ADMIN_APPROVAL);
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    @Test
    void historyUsesTheAccountMobileToMatchGuestOrders() {
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer("9812345678")));
        when(orderRepository.findCustomerHistory(ALICE, "9812345678"))
                .thenReturn(List.of(
                        order(1L, "SHR-1", "9812345678", ALICE),
                        order(2L, "SHR-2", "9812345678", null)));

        List<AccountOrderSummary> history = service.history(ALICE);

        // Both the account-linked and the historical guest order (mobile match) show up.
        assertThat(history).extracting(AccountOrderSummary::orderCode)
                .containsExactly("SHR-1", "SHR-2");
    }

    @Test
    void historyWithNoMobileStillPassesNullToRepo() {
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer(null)));
        when(orderRepository.findCustomerHistory(ALICE, null))
                .thenReturn(List.of(order(1L, "SHR-1", "9812345678", ALICE)));

        List<AccountOrderSummary> history = service.history(ALICE);

        assertThat(history).hasSize(1);
    }

    @Test
    void detailReturnsTheCustomersOwnOrder() {
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer("9812345678")));
        when(orderRepository.findCustomerOrderByCode("SHR-1", ALICE, "9812345678"))
                .thenReturn(Optional.of(order(1L, "SHR-1", "9812345678", ALICE)));

        OrderResponse response = service.detail(ALICE, "SHR-1");

        assertThat(response.orderCode()).isEqualTo("SHR-1");
    }

    @Test
    void detailForAnotherCustomersOrderIsNotFound() {
        when(userRepository.findById(ALICE)).thenReturn(Optional.of(customer("9812345678")));
        // Repo scoping returns empty when the code is not the customer's order.
        when(orderRepository.findCustomerOrderByCode("SHR-9", ALICE, "9812345678"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.detail(ALICE, "SHR-9"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
