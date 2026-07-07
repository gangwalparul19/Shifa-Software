package com.shifa.oms.account;

import com.shifa.oms.account.dto.WishlistItemResponse;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Persisted save-for-later (wishlist) scoped to a registered customer. The
 * {@code customerId} is always the calling customer's user id, so one customer
 * can never see or change another's wishlist.
 *
 * <p>Add is idempotent: saving a product already on the wishlist is a no-op
 * (backed by the {@code (customer_id, product_id)} unique constraint). Remove is
 * likewise idempotent — removing a product that is not present succeeds quietly.
 */
@Service
public class WishlistService {

    private final WishlistItemRepository wishlistRepository;
    private final ProductRepository productRepository;

    public WishlistService(WishlistItemRepository wishlistRepository,
                           ProductRepository productRepository) {
        this.wishlistRepository = wishlistRepository;
        this.productRepository = productRepository;
    }

    @Transactional(readOnly = true)
    public List<WishlistItemResponse> list(Long customerId) {
        return wishlistRepository.findByCustomerIdOrderByIdDesc(customerId).stream()
                .map(item -> productRepository.findById(item.getProductId()))
                .flatMap(Optional::stream)
                .map(WishlistItemResponse::from)
                .toList();
    }

    /** Adds a product to the wishlist; a no-op when it is already present (idempotent). */
    @Transactional
    public void add(Long customerId, Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ValidationException("Product " + productId + " does not exist."));
        if (!wishlistRepository.existsByCustomerIdAndProductId(customerId, product.getId())) {
            wishlistRepository.save(new WishlistItem(customerId, product.getId()));
        }
    }

    /** Removes a product from the wishlist; a no-op when it is not present (idempotent). */
    @Transactional
    public void remove(Long customerId, Long productId) {
        wishlistRepository.deleteByCustomerIdAndProductId(customerId, productId);
    }
}
