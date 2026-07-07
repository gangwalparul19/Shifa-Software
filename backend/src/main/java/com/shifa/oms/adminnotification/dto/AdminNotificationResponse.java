package com.shifa.oms.adminnotification.dto;

import com.shifa.oms.adminnotification.AdminNotification;

import java.time.LocalDateTime;

/**
 * Read projection for a single admin notification
 * ({@code GET /api/admin/notifications}, "operations depth" Feature 2).
 */
public record AdminNotificationResponse(
        Long id,
        String type,
        String title,
        String detail,
        String severity,
        Long orderId,
        String orderCode,
        boolean read,
        LocalDateTime createdAt,
        LocalDateTime readAt
) {

    public static AdminNotificationResponse from(AdminNotification n) {
        return new AdminNotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getDetail(),
                n.getSeverity(),
                n.getOrderId(),
                n.getOrderCode(),
                n.isRead(),
                n.getCreatedAt(),
                n.getReadAt());
    }
}
