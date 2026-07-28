package com.shifa.oms.order;

import com.shifa.oms.product.ProductService;
import com.shifa.oms.product.dto.ProductResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Order-entry product suggestions (sales-productivity wave, Tranche 3):
 * <ul>
 *   <li><b>Favorites / quick-add</b> — the best-selling products (optionally the
 *       salesperson's own), so common items are one tap away;</li>
 *   <li><b>Frequently bought together</b> — products that co-occur in past orders
 *       with what's already in the cart, to grow basket size.</li>
 * </ul>
 * Kept separate from {@link OrderService} so its constructor (and unit tests)
 * stay untouched. Both derive purely from existing order-line history.
 */
@Service
public class OrderSuggestionService {

    private static final int MAX_TOP = 20;
    private static final int MAX_RELATED = 10;

    private final OrderRepository orderRepository;
    private final ProductService productService;

    public OrderSuggestionService(OrderRepository orderRepository, ProductService productService) {
        this.orderRepository = orderRepository;
        this.productService = productService;
    }

    /**
     * Best-selling products for quick-add. When {@code createdBy} is non-null the
     * ranking is limited to that salesperson's own orders (their favorites);
     * null ranks across the whole business.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> topProducts(Long createdBy, int limit) {
        Pageable page = PageRequest.of(0, clamp(limit, MAX_TOP));
        List<Long> ids = createdBy == null
                ? orderRepository.topSoldProductIds(page)
                : orderRepository.topSoldProductIdsByCreator(createdBy, page);
        return productService.byIds(ids);
    }

    /**
     * Products frequently bought together with the given cart products, ranked by
     * co-occurrence. Returns an empty list when the cart is empty.
     */
    @Transactional(readOnly = true)
    public List<ProductResponse> relatedProducts(List<Long> productIds, int limit) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        Pageable page = PageRequest.of(0, clamp(limit, MAX_RELATED));
        return productService.byIds(orderRepository.relatedProductIds(productIds, page));
    }

    private static int clamp(int requested, int max) {
        if (requested < 1) {
            return Math.min(8, max);
        }
        return Math.min(requested, max);
    }
}
