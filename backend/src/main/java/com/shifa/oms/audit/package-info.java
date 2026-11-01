/**
 * Global audit trail ("operations depth" Feature 3): a central, read-only record
 * of who did what, when.
 *
 * <p>{@link com.shifa.oms.audit.AuditService#record} is called (best-effort,
 * never-throwing) from the key mutating operations across the platform — order
 * approve/reject, staff user management, settings update, and stock
 * restock/adjust — resolving the actor from the security context. (Coupon
 * create/deactivate actions are legacy: the coupon module was removed, but its
 * action codes are kept for historical audit rows.)
 * {@link com.shifa.oms.audit.AuditController} exposes the filtered, paged,
 * newest-first trail at {@code /api/admin/audit} (ADMIN only).
 */
package com.shifa.oms.audit;
