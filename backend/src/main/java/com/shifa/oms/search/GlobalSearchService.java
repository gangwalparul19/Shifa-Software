package com.shifa.oms.search;

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

/**
 * Read-only unified search across orders, products, and customers for the admin
 * omni-search (ROADMAP 2.2 "Wave 2").
 *
 * <p>Reuses the existing repository finders — {@link OrderRepository#search}
 * (unscoped, matching code / customer / mobile / id / AWB),
 * {@link ProductRepository#searchAllByNameOrSku} (name / SKU across published +
 * hidden), and {@link UserRepository#searchCustomers} (registered CUSTOMER
 * accounts by name / mobile) — capping each group to {@link #GROUP_LIMIT}
 * results. A blank query short-circuits to empty groups without touching the
 * database.
 */
@Service
public class GlobalSearchService {

    /** Maximum results returned per group. */
    public static final int GROUP_LIMIT = 8;

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public GlobalSearchService(OrderRepository orderRepository,
                               ProductRepository productRepository,
                               UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    /** Runs the unified search; a blank term returns all-empty groups. */
    @Transactional(readOnly = true)
    public GlobalSearchResponse search(String q) {
        if (q == null || q.isBlank()) {
            return GlobalSearchResponse.empty();
        }
        String term = q.trim();

        List<GlobalSearchResponse.OrderHit> orders =
                orderRepository.search(term, null).stream()
                        .limit(GROUP_LIMIT)
                        .map(GlobalSearchService::toOrderHit)
                        .toList();

        List<GlobalSearchResponse.ProductHit> products =
                productRepository.searchAllByNameOrSku(term).stream()
                        .limit(GROUP_LIMIT)
                        .map(GlobalSearchService::toProductHit)
                        .toList();

        List<GlobalSearchResponse.CustomerHit> customers =
                userRepository.searchCustomers(term, PageRequest.of(0, GROUP_LIMIT)).stream()
                        .map(GlobalSearchService::toCustomerHit)
                        .toList();

        return new GlobalSearchResponse(orders, products, customers);
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
