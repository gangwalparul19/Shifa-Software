package com.shifa.oms.product;

/**
 * Derived stock/availability status for a product.
 *
 * <p>This is pure logic (no persistence) so it can be unit-tested in isolation
 * and reused by both the catalog responses and the storefront badges. The rule
 * (see {@link #of(boolean, int)}):
 *
 * <ul>
 *   <li>When a product opts out of inventory tracking ({@code trackInventory ==
 *       false}) it is <em>always</em> {@link #IN_STOCK} regardless of quantity.</li>
 *   <li>When tracking is on: quantity &le; 0 is {@link #OUT_OF_STOCK}; a small
 *       positive quantity (0 &lt; qty &le; {@link #LOW_STOCK_THRESHOLD}) is
 *       {@link #LOW_STOCK}; anything higher is {@link #IN_STOCK}.</li>
 * </ul>
 */
public enum StockStatus {
    IN_STOCK,
    LOW_STOCK,
    OUT_OF_STOCK;

    /** At or below this on-hand quantity a tracked product is considered low. */
    public static final int LOW_STOCK_THRESHOLD = 5;

    /**
     * Computes the stock status from the inventory flag and on-hand quantity
     * using the default {@link #LOW_STOCK_THRESHOLD}.
     */
    public static StockStatus of(boolean trackInventory, int quantity) {
        return of(trackInventory, quantity, LOW_STOCK_THRESHOLD);
    }

    /**
     * Computes the stock status with an explicit low-stock threshold.
     *
     * @param trackInventory whether this product tracks stock at all
     * @param quantity       on-hand units (ignored when not tracking)
     * @param lowThreshold   inclusive upper bound for {@link #LOW_STOCK}
     */
    public static StockStatus of(boolean trackInventory, int quantity, int lowThreshold) {
        if (!trackInventory) {
            return IN_STOCK;
        }
        if (quantity <= 0) {
            return OUT_OF_STOCK;
        }
        if (quantity <= lowThreshold) {
            return LOW_STOCK;
        }
        return IN_STOCK;
    }

    /** Whether a product with this status can be added to the cart / purchased. */
    public boolean isPurchasable() {
        return this != OUT_OF_STOCK;
    }
}
