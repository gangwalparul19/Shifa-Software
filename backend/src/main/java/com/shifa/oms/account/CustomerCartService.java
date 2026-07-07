package com.shifa.oms.account;

import com.shifa.oms.account.dto.CartItemResponse;
import com.shifa.oms.product.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted cart scoped to a registered customer, so a signed-in customer's cart
 * survives across devices (ROADMAP 1.2). The {@code customerId} is always the
 * calling customer's user id, so one customer can never see or change another's
 * cart.
 *
 * <p>The cart is stored as a set of {@code (product_id, quantity)} rows and is
 * always mutated as a whole via {@link #replace(Long, List)}: the posted lines
 * atomically replace whatever was saved before (delete-then-insert in one
 * transaction). This keeps the server cart a faithful mirror of the client cart
 * and makes {@code replace} idempotent — posting the same lines twice yields the
 * same saved cart.
 *
 * <p>Invalid lines are skipped gracefully rather than failing the whole request:
 * a line whose product no longer exists, or whose quantity is outside 1..999, is
 * ignored so a stale client cart can still be saved (minus the bad lines).
 */
@Service
public class CustomerCartService {

    /** Minimum saleable quantity for a cart line (matches the DB check constraint). */
    static final int MIN_QUANTITY = 1;
    /** Maximum saleable quantity for a cart line (matches the DB check constraint). */
    static final int MAX_QUANTITY = 999;

    private final CustomerCartItemRepository cartRepository;
    private final ProductRepository productRepository;

    public CustomerCartService(CustomerCartItemRepository cartRepository,
                               ProductRepository productRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
    }

    /**
     * The customer's saved cart as product summaries + quantity. Products that no
     * longer exist are skipped so a deleted product never breaks the cart view.
     */
    @Transactional(readOnly = true)
    public List<CartItemResponse> list(Long customerId) {
        return cartRepository.findByCustomerIdOrderByIdAsc(customerId).stream()
                .flatMap(item -> productRepository.findById(item.getProductId())
                        .map(product -> CartItemResponse.from(product, item.getQuantity()))
                        .stream())
                .toList();
    }

    /**
     * Atomically replaces the customer's saved cart with the posted lines. Existing
     * rows for the customer are deleted, then the valid posted lines are inserted.
     *
     * <p>Each line is validated: the product must exist and the quantity must be
     * within 1..999; invalid lines are skipped (not an error). Duplicate product
     * ids in the payload collapse to a single line (the last quantity wins) so the
     * {@code (customer_id, product_id)} unique constraint is never violated.
     *
     * @param customerId the owning customer's user id
     * @param lines      the desired cart lines
     * @return the resulting saved cart as product summaries + quantity
     */
    @Transactional
    public List<CartItemResponse> replace(Long customerId, List<CartLine> lines) {
        cartRepository.deleteByCustomerId(customerId);

        // Collapse duplicate product ids (last quantity wins) and drop invalid lines,
        // preserving first-seen ordering so the restored cart is stable.
        Map<Long, Integer> valid = new LinkedHashMap<>();
        if (lines != null) {
            for (CartLine line : lines) {
                if (line == null || line.productId() == null) {
                    continue;
                }
                if (line.quantity() < MIN_QUANTITY || line.quantity() > MAX_QUANTITY) {
                    continue;
                }
                if (!productRepository.existsById(line.productId())) {
                    continue;
                }
                valid.put(line.productId(), line.quantity());
            }
        }

        valid.forEach((productId, quantity) ->
                cartRepository.save(new CustomerCartItem(customerId, productId, quantity)));

        return list(customerId);
    }

    /**
     * Empties the customer's saved cart. Idempotent: clearing an already-empty
     * cart is a no-op (the delete simply removes zero rows).
     *
     * @param customerId the owning customer's user id
     */
    @Transactional
    public void clearCart(Long customerId) {
        cartRepository.deleteByCustomerId(customerId);
    }

    /** A single desired cart line: a product and a quantity. */
    public record CartLine(Long productId, int quantity) {
    }
}
