package com.shifa.oms.adminnotification.dto;

/**
 * Response for {@code GET /api/admin/notifications/unread-count}: the number of
 * unread admin notifications for the console badge.
 */
public record UnreadCountResponse(long unreadCount) {
}
