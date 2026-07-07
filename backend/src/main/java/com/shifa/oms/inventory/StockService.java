package com.shifa.oms.inventory;

import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import com.shifa.oms.product.Product;
import com.shifa.oms.product.ProductRepository;
import com.shifa.oms.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory application service: the single owner of every change to a product's
 * on-hand quantity. Each mutation updates {@link Product#getStockQuantity()} and
 * records exactly one {@link StockMovement} ledger row atomically (Feature 1),
 * and emits a best-effort low-stock admin notification when a decrement crosses
 * a tracked product into the low-stock band.
 *
 * <p>Callers:
 * <ul>
 *   <li>{@code OrderService} calls {@link #recordSale(Product, int, String, Long)}
 *       during order creation to reserve stock for tracked products
 *       (server-side guard: rejects when insufficient);</li>
 *   <li>the admin inventory endpoints call {@link #restock} and
 *       {@link #adjust};</li>
 *   <li>cancellation/RTO restock (optional) would call {@link #returnToStock}.</li>
 * </ul>
 *
 * <p>All money-free integer math; never lets stock go negative.
 */
@Service
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final ProductRepository productRepository;
    private final StockMovementRepository stockMovementRepository;
    private final OutboxEventPublisher outboxEventPublisher;
    private final SettingsService settingsService;

    public StockService(ProductRepository productRepository,
                        StockMovementRepository stockMovementRepository,
                        OutboxEventPublisher outboxEventPublisher,
                        SettingsService settingsService) {
        this.productRepository = productRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        this.settingsService = settingsService;
    }

    /**
     * Applies a signed stock adjustment to a product and records a movement row
     * atomically. A negative delta may not drive the on-hand quantity below zero.
     *
     * @param productId the product to adjust
     * @param delta     signed change (+restock/+return, −adjustment/−sale)
     * @param type      the movement type recorded on the ledger row
     * @param reason    a human-readable reason (may be {@code null})
     * @param userId    the acting user id, or {@code null} for system movements
     * @return the recorded movement
     */
    @Transactional
    public StockMovement adjust(Long productId, int delta, StockMovementType type,
                                String reason, Long userId) {
        Product product = requireProduct(productId);
        int before = product.getStockQuantity();
        int after = before + delta;
        if (after < 0) {
            throw new ValidationException(
                    "Adjustment would drive stock negative for product '" + product.getName()
                            + "' (on-hand " + before + ", delta " + delta + ").");
        }
        product.setStockQuantity(after);
        productRepository.save(product);
        StockMovement movement = stockMovementRepository.save(
                new StockMovement(product.getId(), delta, type, reason, after, userId));
        if (delta < 0) {
            maybeNotifyLowStock(product, before, after);
        }
        return movement;
    }

    /**
     * Adds stock to a product (a RESTOCK movement). Quantity must be positive.
     *
     * @param productId the product to restock
     * @param quantity  the positive number of units to add
     * @param reason    a human-readable reason (may be {@code null})
     * @param userId    the acting user id, or {@code null}
     * @return the recorded movement
     */
    @Transactional
    public StockMovement restock(Long productId, int quantity, String reason, Long userId) {
        if (quantity <= 0) {
            throw new ValidationException("Restock quantity must be a positive number.");
        }
        return adjust(productId, quantity, StockMovementType.RESTOCK, reason, userId);
    }

    /**
     * Returns units to stock (a RETURN movement), e.g. for a cancelled/RTO order.
     *
     * @param productId the product to restock
     * @param quantity  the positive number of units returned
     * @param reason    a human-readable reason (may be {@code null})
     * @param userId    the acting user id, or {@code null}
     * @return the recorded movement
     */
    @Transactional
    public StockMovement returnToStock(Long productId, int quantity, String reason, Long userId) {
        if (quantity <= 0) {
            throw new ValidationException("Return quantity must be a positive number.");
        }
        return adjust(productId, quantity, StockMovementType.RETURN, reason, userId);
    }

    /**
     * Decrements a tracked product's stock for a placed order and records a SALE
     * movement, all within the caller's (order-creation) transaction. Products
     * that do not track inventory are ignored (never decremented). A tracked
     * product with insufficient stock is rejected with a {@link ValidationException}
     * — the server-side guard that mirrors the storefront's out-of-stock block.
     *
     * @param product  the ordered product (managed within the current tx)
     * @param quantity the quantity ordered (positive)
     * @param reason   a human-readable reason (e.g. the order code)
     * @param userId   the acting user id, or {@code null} for storefront orders
     */
    @Transactional
    public void recordSale(Product product, int quantity, String reason, Long userId) {
        if (product == null || !product.isTrackInventory()) {
            return;
        }
        int before = product.getStockQuantity();
        if (before < quantity) {
            throw new ValidationException(
                    "Insufficient stock for '" + product.getName() + "' (on-hand " + before
                            + ", requested " + quantity + ").");
        }
        int after = before - quantity;
        product.setStockQuantity(after);
        productRepository.save(product);
        stockMovementRepository.save(new StockMovement(
                product.getId(), -quantity, StockMovementType.SALE, reason, after, userId));
        maybeNotifyLowStock(product, before, after);
    }

    /**
     * The effective low-stock threshold for a product: its per-product override
     * when set, else the settings-level default.
     */
    @Transactional(readOnly = true)
    public int thresholdFor(Product product) {
        Integer override = product.getLowStockThreshold();
        if (override != null) {
            return override;
        }
        return settingsService.getSettings().getLowStockThreshold();
    }

    /**
     * Whether a product is currently low on stock: tracked and
     * {@code 0 < stockQuantity <= threshold}.
     */
    @Transactional(readOnly = true)
    public boolean isLowStock(Product product) {
        if (!product.isTrackInventory()) {
            return false;
        }
        int qty = product.getStockQuantity();
        return qty > 0 && qty <= thresholdFor(product);
    }

    /** Recent movements for a product, newest first. */
    @Transactional(readOnly = true)
    public java.util.List<StockMovement> movements(Long productId) {
        requireProduct(productId);
        return stockMovementRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId);
    }

    /** All products for the admin inventory grid, ordered by name. */
    @Transactional(readOnly = true)
    public java.util.List<Product> inventory() {
        return productRepository.findAllByOrderByNameAsc();
    }

    /**
     * Tracked products that are low on stock (and optionally out of stock).
     *
     * @param includeOutOfStock when {@code true} also includes tracked products
     *                          with zero on-hand quantity
     * @return the matching products, ordered by name
     */
    @Transactional(readOnly = true)
    public java.util.List<Product> lowStock(boolean includeOutOfStock) {
        java.util.List<Product> result = new java.util.ArrayList<>();
        for (Product product : productRepository.findAllByOrderByNameAsc()) {
            if (!product.isTrackInventory()) {
                continue;
            }
            int threshold = thresholdFor(product);
            int qty = product.getStockQuantity();
            if (qty > 0 && qty <= threshold) {
                result.add(product);
            } else if (includeOutOfStock && qty <= 0) {
                result.add(product);
            }
        }
        return result;
    }

    /** Loads a single product for building an inventory response. */
    @Transactional(readOnly = true)
    public Product product(Long productId) {
        return requireProduct(productId);
    }

    // --- Internal helpers ---------------------------------------------------

    private Product requireProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product " + productId + " does not exist."));
    }

    /**
     * Emits a best-effort low-stock admin notification when a decrement crosses a
     * tracked product from above the threshold into the low-stock (or
     * out-of-stock) band. Never throws — inventory changes must not fail because a
     * notification could not be enqueued.
     */
    private void maybeNotifyLowStock(Product product, int before, int after) {
        if (!product.isTrackInventory()) {
            return;
        }
        int threshold = thresholdFor(product);
        boolean crossedIn = before > threshold && after <= threshold;
        if (!crossedIn) {
            return;
        }
        try {
            outboxEventPublisher.publishLowStock(
                    product.getId(), product.getSku(), product.getName(),
                    after, threshold, after <= 0);
        } catch (RuntimeException e) {
            log.warn("Failed to enqueue low-stock notification for product {}: {}",
                    product.getId(), e.getMessage());
        }
    }
}
