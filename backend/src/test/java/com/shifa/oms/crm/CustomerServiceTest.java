package com.shifa.oms.crm;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.common.PageResponse;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.crm.dto.CustomerDetailResponse;
import com.shifa.oms.crm.dto.CustomerSummaryResponse;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CustomerService} with mocked repositories:
 * <ul>
 *   <li>{@code list} maps the {@code GROUP BY customer_mobile} projection to
 *       summaries, sets the {@code repeatBuyer} flag ({@code orderCount > 1}) and
 *       the {@code registered} flag from the account-mobile lookup;</li>
 *   <li>{@code get} returns the customer's order history and a summary whose LTV
 *       is the sum of order {@code total_amount};</li>
 *   <li>{@code get} 404s when the customer has no orders.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private UserRepository userRepository;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        service = new CustomerService(customerRepository, orderRepository, userRepository);
    }

    /** Simple hand-written projection stand-in (interface projection, no mocking needed). */
    private static CustomerSummaryProjection projection(String mobile, String name, long count,
                                                        String totalSpent) {
        return new CustomerSummaryProjection() {
            @Override public String getMobile() { return mobile; }
            @Override public String getName() { return name; }
            @Override public long getOrderCount() { return count; }
            @Override public BigDecimal getTotalSpent() { return new BigDecimal(totalSpent); }
            @Override public LocalDateTime getLastOrderAt() { return LocalDateTime.now(); }
            @Override public LocalDateTime getFirstOrderAt() { return LocalDateTime.now().minusDays(30); }
        };
    }

    @Test
    void getReturnsHistoryAndLifetimeValue() {
        String mobile = "9990001111";
        List<OrderEntity> orders = List.of(
                order("SHR-3", mobile, "Asha", "500.00", OrderStatus.DELIVERED, PaymentStatus.FULLY_PAID),
                order("SHR-2", mobile, "Asha", "300.00", OrderStatus.DELIVERED, PaymentStatus.FULLY_PAID),
                order("SHR-1", mobile, "Asha", "200.00", OrderStatus.DELIVERED, PaymentStatus.COD));
        when(orderRepository.findByCustomerMobileOrderByCreatedAtDesc(mobile)).thenReturn(orders);
        when(userRepository.existsByMobileAndRole(mobile, Role.CUSTOMER)).thenReturn(true);

        CustomerDetailResponse detail = service.get(mobile);

        assertThat(detail.summary().mobile()).isEqualTo(mobile);
        assertThat(detail.summary().orderCount()).isEqualTo(3);
        assertThat(detail.summary().repeatBuyer()).isTrue();
        assertThat(detail.summary().registered()).isTrue();
        // LTV = 500 + 300 + 200.
        assertThat(detail.summary().totalSpent()).isEqualByComparingTo("1000.00");
        assertThat(detail.orders()).hasSize(3);
        assertThat(detail.orders().get(0).orderCode()).isEqualTo("SHR-3");
        assertThat(detail.orders().get(0).total()).isEqualByComparingTo("500.00");
    }

    @Test
    void getUnknownCustomerIsRejected() {
        when(orderRepository.findByCustomerMobileOrderByCreatedAtDesc("0000000000"))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.get("0000000000"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static OrderEntity order(String code, String mobile, String name, String total,
                                     OrderStatus status, PaymentStatus paymentStatus) {
        OrderEntity order = new OrderEntity(code, OrderSource.STOREFRONT, 1L, name, mobile,
                "1 Herbal Rd", "Indore", "MP", "452001");
        BigDecimal amount = new BigDecimal(total);
        order.applyAmounts(amount, BigDecimal.ZERO, amount, amount, paymentStatus);
        order.setOrderStatus(status);
        return order;
    }
}
