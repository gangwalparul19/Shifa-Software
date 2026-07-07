package com.shifa.oms.search;

import com.shifa.oms.auth.Role;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.product.ProductVisibility;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link GlobalSearchService} (ROADMAP 2.2 admin
 * global search): grouped matches are returned, a blank query short-circuits to
 * empty groups (no repository access), and each group is capped at
 * {@link GlobalSearchService#GROUP_LIMIT}. Only interface repositories are
 * mocked (per the JVM Mockito constraint).
 */
@ExtendWith(MockitoExtension.class)
class GlobalSearchServiceTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private ProductRepository productRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private GlobalSearchService service;

    private OrderEntity order(long id, String code) {
        OrderEntity o = new OrderEntity(code, OrderSource.STOREFRONT, null,
                "Asha Kumar", "9812345678", "12 MG Road", "Pune", "Maharashtra", "411001");
        o.applyAmounts(new BigDecimal("240.00"), BigDecimal.ZERO,
                new BigDecimal("240.00"), new BigDecimal("240.00"), PaymentStatus.COD);
        o.setOrderStatus(OrderStatus.PENDING_ADMIN_APPROVAL);
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    private Product product(long id, String sku, String name) {
        Product p = new Product(sku, name, "d", new BigDecimal("999.00"),
                new BigDecimal("499.00"), ProductVisibility.PUBLISHED);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private User customer(long id, String name, String mobile) {
        User u = new User("cust" + id, "hash", Role.CUSTOMER, name, null, mobile, true);
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    void returnsGroupedMatches() {
        when(orderRepository.search(eq("asha"), isNull()))
                .thenReturn(List.of(order(1L, "SHR-000001")));
        when(productRepository.searchAllByNameOrSku("asha"))
                .thenReturn(List.of(product(10L, "ASH-1", "Ashwagandha")));
        when(userRepository.searchCustomers(eq("asha"), any(Pageable.class)))
                .thenReturn(List.of(customer(20L, "Asha Kumar", "9812345678")));

        GlobalSearchResponse result = service.search("asha");

        assertThat(result.orders()).singleElement().satisfies(h -> {
            assertThat(h.id()).isEqualTo(1L);
            assertThat(h.orderCode()).isEqualTo("SHR-000001");
            assertThat(h.customerName()).isEqualTo("Asha Kumar");
            assertThat(h.orderStatus()).isEqualTo(OrderStatus.PENDING_ADMIN_APPROVAL);
            assertThat(h.totalAmount()).isEqualByComparingTo("240.00");
        });
        assertThat(result.products()).singleElement().satisfies(h -> {
            assertThat(h.id()).isEqualTo(10L);
            assertThat(h.sku()).isEqualTo("ASH-1");
            assertThat(h.name()).isEqualTo("Ashwagandha");
        });
        assertThat(result.customers()).singleElement().satisfies(h -> {
            assertThat(h.id()).isEqualTo(20L);
            assertThat(h.name()).isEqualTo("Asha Kumar");
            assertThat(h.mobile()).isEqualTo("9812345678");
        });
    }

    @Test
    void blankQueryReturnsEmptyGroupsWithoutRepositoryAccess() {
        GlobalSearchResponse result = service.search("   ");

        assertThat(result.orders()).isEmpty();
        assertThat(result.products()).isEmpty();
        assertThat(result.customers()).isEmpty();
        verifyNoInteractions(orderRepository, productRepository, userRepository);
    }

    @Test
    void nullQueryReturnsEmptyGroups() {
        GlobalSearchResponse result = service.search(null);

        assertThat(result.orders()).isEmpty();
        assertThat(result.products()).isEmpty();
        assertThat(result.customers()).isEmpty();
    }

    @Test
    void ordersAndProductsGroupsAreCapped() {
        List<OrderEntity> manyOrders = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> order(i, "SHR-" + String.format("%06d", i)))
                .toList();
        List<Product> manyProducts = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> product(100 + i, "SKU-" + i, "Product " + i))
                .toList();
        when(orderRepository.search(eq("a"), isNull())).thenReturn(manyOrders);
        when(productRepository.searchAllByNameOrSku("a")).thenReturn(manyProducts);
        when(userRepository.searchCustomers(eq("a"), any(Pageable.class))).thenReturn(List.of());

        GlobalSearchResponse result = service.search("a");

        assertThat(result.orders()).hasSize(GlobalSearchService.GROUP_LIMIT);
        assertThat(result.products()).hasSize(GlobalSearchService.GROUP_LIMIT);
    }

    @Test
    void customersGroupCapIsDelegatedToRepositoryPageable() {
        when(orderRepository.search(eq("a"), isNull())).thenReturn(List.of());
        when(productRepository.searchAllByNameOrSku("a")).thenReturn(List.of());
        when(userRepository.searchCustomers(eq("a"), any(Pageable.class))).thenReturn(List.of());

        service.search("a");

        // The customer group cap is enforced at the query via a size-limited Pageable.
        verify(userRepository).searchCustomers(eq("a"),
                eq(org.springframework.data.domain.PageRequest.of(0, GlobalSearchService.GROUP_LIMIT)));
        verify(orderRepository, never()).findAll();
    }
}
