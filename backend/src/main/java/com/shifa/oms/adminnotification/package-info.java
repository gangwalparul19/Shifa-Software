/**
 * Admin notifications center ("operations depth" Feature 2): a durable history
 * of admin-facing alerts with read/unread state.
 *
 * <p>Admin alerts were previously ephemeral (relayed over SSE only). The
 * {@link com.shifa.oms.adminnotification.AdminNotificationOutboxSink} hooks into
 * {@link com.shifa.oms.platform.outbox.OutboxEventPublisher} (via the
 * {@link com.shifa.oms.platform.outbox.OutboxEventSink} extension point) to
 * persist an {@link com.shifa.oms.adminnotification.AdminNotification} row for
 * each admin event as it is published — de-duplicated on the outbox event id and
 * best-effort, so it never breaks the originating operation and never disturbs
 * the SSE relay, which keeps consuming the same rows independently.
 * {@link com.shifa.oms.adminnotification.AdminNotificationController} exposes the
 * list / unread-count / mark-read / mark-all-read API at
 * {@code /api/admin/notifications} (ADMIN only).
 */
package com.shifa.oms.adminnotification;
