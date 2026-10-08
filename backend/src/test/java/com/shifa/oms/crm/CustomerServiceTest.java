package com.shifa.oms.crm;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.SalespersonScopeResolver;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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
        // Real CurrentUserService (reads the SecurityContext) + scope resolver so the
        // salesperson-scoping path is exercised exactly as in production. With no
        // authentication set, currentUser() is empty → unscoped (ADMIN/ACCOUNTANT).
        service = new CustomerService(customerRepository, orderRepository, userRepository,
                new CurrentUserService(), new SalespersonScopeResolver());
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** Authenticates the current thread as {@code principal} (drives scoping). */
    private static void authenticateAs(AuthPrincipal principal) {
        var token = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority(principal.role().authority())));
        SecurityContextHolder.getContext().setAuthentication(token);
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

    // --- Salesperson scoping (Req 5.4, 5.5) --------------------------------

    /**
     * A SALESPERSON's list aggregation is constrained to orders they created:
     * the {@code createdBy} constraint passed to the repository is their own
     * user id (not null), so they only see their own customers.
     */
    @Test
    void salespersonListIsScopedToOwnOrders() {
        long salesId = 7L;
        authenticateAs(new AuthPrincipal(salesId, "sales1", Role.SALESPERSON));
        when(customerRepository.aggregateAll(null, salesId))
                .thenReturn(List.of(projection("9990001111", "Asha", 2, "800.00")));
        when(userRepository.findCustomerMobilesIn(any())).thenReturn(List.of());

        PageResponse<CustomerSummaryResponse> page = service.list(null, PageRequest.of(0, 20));

        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).mobile()).isEqualTo("9990001111");
        // The aggregation was scoped to the salesperson's own created_by id.
        verify(customerRepository).aggregateAll(null, salesId);
    }

    /** An ADMIN's list aggregation is unscoped ({@code createdBy} constraint is null). */
    @Test
    void adminListIsUnscoped() {
        authenticateAs(new AuthPrincipal(1L, "admin", Role.ADMIN));
        when(customerRepository.aggregateAll(null, null))
                .thenReturn(List.of(projection("9990001111", "Asha", 1, "200.00")));
        when(userRepository.findCustomerMobilesIn(any())).thenReturn(List.of());

        service.list(null, PageRequest.of(0, 20));

        verify(customerRepository).aggregateAll(null, null);
    }

    /**
     * A SALESPERSON's customer detail is restricted to orders they created: an
     * order created by a different salesperson is filtered out of the history and
     * the totals.
     */
    @Test
    void salespersonGetSeesOnlyOwnOrders() {
        long salesId = 7L;
        String mobile = "9990002222";
        authenticateAs(new AuthPrincipal(salesId, "sales1", Role.SALESPERSON));
        List<OrderEntity> orders = List.of(
                order("SHR-9", salesId, mobile, "Asha", "500.00", OrderStatus.DELIVERED, PaymentStatus.FULLY_PAID),
                order("SHR-8", 99L, mobile, "Asha", "300.00", OrderStatus.DELIVERED, PaymentStatus.FULLY_PAID));
        when(orderRepository.findByCustomerMobileOrderByCreatedAtDesc(mobile)).thenReturn(orders);
        when(userRepository.existsByMobileAndRole(mobile, Role.CUSTOMER)).thenReturn(false);

        CustomerDetailResponse detail = service.get(mobile);

        // Only the salesperson's own order is visible; the other salesperson's is hidden.
        assertThat(detail.orders()).hasSize(1);
        assertThat(detail.orders().get(0).orderCode()).isEqualTo("SHR-9");
        assertThat(detail.summary().orderCount()).isEqualTo(1);
        assertThat(detail.summary().totalSpent()).isEqualByComparingTo("500.00");
    }

    /**
     * A customer whose orders were all created by someone else is invisible to a
     * salesperson — indistinguishable from a non-existent customer (404).
     */
    @Test
    void salespersonCannotSeeAnotherSalespersonsCustomer() {
        long salesId = 7L;
        String mobile = "9990003333";
        authenticateAs(new AuthPrincipal(salesId, "sales1", Role.SALESPERSON));
        when(orderRepository.findByCustomerMobileOrderByCreatedAtDesc(mobile))
                .thenReturn(List.of(order("SHR-7", 99L, mobile, "Bina", "150.00",
                        OrderStatus.DELIVERED, PaymentStatus.COD)));

        assertThatThrownBy(() -> service.get(mobile))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static OrderEntity order(String code, String mobile, String name, String total,
                                     OrderStatus status, PaymentStatus paymentStatus) {
        return order(code, 1L, mobile, name, total, status, paymentStatus);
    }

    private static OrderEntity order(String code, Long createdBy, String mobile, String name,
                                     String total, OrderStatus status, PaymentStatus paymentStatus) {
        OrderEntity order = new OrderEntity(code, OrderSource.STOREFRONT, createdBy, name, mobile,
                "1 Herbal Rd", "Indore", "MP", "452001");
        BigDecimal amount = new BigDecimal(total);
        order.applyAmounts(amount, BigDecimal.ZERO, amount, amount, paymentStatus);
        order.setOrderStatus(status);
        return order;
    }
}
