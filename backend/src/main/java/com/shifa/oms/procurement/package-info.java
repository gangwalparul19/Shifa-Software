/**
 * Procurement module (Feature C2): suppliers and purchase orders.
 *
 * <p>Suppliers are the vendors we buy stock from; a purchase order (PO) captures
 * what was ordered from a supplier and at what unit cost. Receiving a PO feeds
 * inventory through {@link com.shifa.oms.inventory.StockService#restock} (a
 * RESTOCK movement per received quantity), so on-hand stock increases and the
 * PO progresses ORDERED &rarr; PARTIALLY_RECEIVED &rarr; RECEIVED. PO numbers
 * (PO-0001, ...) are allocated from a single-row, row-locked sequence table,
 * mirroring the invoice-number pattern.
 *
 * <p>All endpoints live under {@code /api/admin/*} and are ADMIN only.
 */
package com.shifa.oms.procurement;
