package com.shifa.oms.order.dto;

/**
 * Result of uploading a payment screenshot via
 * {@code POST /api/orders/payment-screenshots}: the storage key to reference
 * from a subsequent {@code POST /api/orders} (two-step upload, Req 7.6, 7.11).
 */
public record ScreenshotUploadResponse(String key) {
}
