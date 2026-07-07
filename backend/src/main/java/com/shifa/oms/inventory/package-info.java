/**
 * Inventory / stock-management module (Batch 1, Feature 1).
 *
 * <p>Owns the {@code stock_movements} ledger and every change to a product's
 * on-hand quantity: auto-decrement on order (reserve at creation), admin
 * restock/adjustment entries, low-stock detection, and best-effort low-stock
 * admin notifications via the existing transactional outbox / SSE mechanism.
 */
package com.shifa.oms.inventory;
