package com.shifa.oms.search;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.SalespersonScopeResolver;
import com.shifa.oms.auth.User;
import com.shifa.oms.auth.UserRepository;
import com.shifa.oms.order.OrderEntity;
import com.shifa.oms.order.OrderRepository;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Read-only unified search across orders, products, and customers for the admin
 * omni-search (ROADMAP 2.2 "Wave 2").
 *
 * <p>Reuses the existing repository finders — {@link OrderRepository#search}
 * (matching code / customer / mobile / id / AWB),
 * {@link ProductRepository#searchAllByNameOrSku} (name / SKU across published +
 * hidden), and {@link UserRepository#searchCustomers} (registered CUSTOMER
 * accounts by name / mobile) — capping each group to {@link #GROUP_LIMIT}
 * results. A blank query short-circuits to empty groups without touching the
 * database.
 *
 * <p><strong>Scope.</strong> Order hits follow the same rule as the Orders page
 * and {@code OrderService.search}: a salesperson finds only the orders they
 * punched, a team lead only their own and their assigned salespeople's, and
 * admin / accountant / CA / packing see all. The scope is resolved from the
 * signed-in user via {@link SalespersonScopeResolver}, never from the request.
 * Registered-customer-account hits are not tied to any salesperson, so a scoped
 * role does not receive them.
 */
@Service
public class GlobalSearchService {

    /** Maximum results returned per group. */
    public static final int GROUP_LIMIT = 8;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final SalespersonScopeResolver scopeResolver;

    public GlobalSearchService(OrderRepository orderRepository,
                               ProductRepository productRepository,
                               UserRepository userRepository,
                               SalespersonScopeResolver scopeResolver) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.scopeResolver = scopeResolver;
    }

    /** Runs the unified search for {@code actor}; a blank term returns all-empty groups. */
    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String q, AuthPrincipal actor) {
        if (q == null || q.isBlank()) {
            return GlobalSearchResponse.empty();
        }
        String term = q.trim();
        Optional<List<Long>> scope = scopeResolver.creatorScope(actor);

        List<GlobalSearchResponse.OrderHit> orders =
                scopedOrders(term, scope).stream()
                        .limit(GROUP_LIMIT)
                        .map(GlobalSearchService::toOrderHit)
                        .toList();

        List<GlobalSearchResponse.ProductHit> products =
                productRepository.searchAllByNameOrSku(term).stream()
                        .limit(GROUP_LIMIT)
                        .map(GlobalSearchService::toProductHit)
                        .toList();

        List<GlobalSearchResponse.CustomerHit> customers = scope.isPresent()
                ? List.of()
                : userRepository.searchCustomers(term, PageRequest.of(0, GROUP_LIMIT)).stream()
                        .map(GlobalSearchService::toCustomerHit)
                        .toList();

        return new GlobalSearchResponse(orders, products, customers);
    }

    /**
     * Orders matching {@code term} within the caller's scope. An absent scope is
     * unscoped; a present-but-empty scope matches nothing (never "everything").
     */
    private List<OrderEntity> scopedOrders(String term, Optional<List<Long>> scope) {
        if (scope.isEmpty()) {
            return orderRepository.search(term, null);
        }
        List<Long> ids = scope.get();
        if (ids.isEmpty()) {
            return List.of();
        }
        if (ids.size() == 1) {
            return orderRepository.search(term, ids.get(0));
        }
        return orderRepository.searchIn(term, ids);
    }

    private static GlobalSearchResponse.OrderHit toOrderHit(OrderEntity order) {
        return new GlobalSearchResponse.OrderHit(
                order.getId(),
                order.getOrderCode(),
                order.getCustomerName(),
                order.getOrderStatus(),
                order.getTotalAmount());
    }

    private static GlobalSearchResponse.ProductHit toProductHit(Product product) {
        return new GlobalSearchResponse.ProductHit(
                product.getId(), product.getSku(), product.getName());
    }

    private static GlobalSearchResponse.CustomerHit toCustomerHit(User user) {
        return new GlobalSearchResponse.CustomerHit(
                user.getId(), user.getFullName(), user.getMobile());
    }
}
