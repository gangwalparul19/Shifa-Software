/**
 * Packing and barcode scan (Req 11).
 *
 * <p>A packing user scans an approved order's internal-label barcode (the order
 * code) to move it {@code Label_Generated → Packed} through the shared order
 * status state machine. Unrecognized barcodes (404) and orders not in
 * {@code Label_Generated} (409, with the current status) are rejected. Each
 * successful scan persists an {@code ORDER_PACKED} outbox event for the admin
 * real-time notification consumed by the dashboard SSE stream (task 19).
 */
package com.shifa.oms.packing;
