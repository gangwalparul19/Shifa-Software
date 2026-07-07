package com.shifa.oms.inventory;

/**
 * The kind of stock movement recorded in the {@code stock_movements} ledger.
 *
 * <ul>
 *   <li>{@link #RESTOCK} — positive delta, stock added by an admin restock;</li>
 *   <li>{@link #ADJUSTMENT} — manual correction, delta may be positive or
 *       negative (e.g. damage write-off, stock-take fix);</li>
 *   <li>{@link #SALE} — negative delta, stock consumed by a placed order;</li>
 *   <li>{@link #RETURN} — positive delta, stock returned to inventory
 *       (e.g. an RTO or cancellation restock).</li>
 * </ul>
 */
public enum StockMovementType {
    RESTOCK,
    ADJUSTMENT,
    SALE,
    RETURN
}
