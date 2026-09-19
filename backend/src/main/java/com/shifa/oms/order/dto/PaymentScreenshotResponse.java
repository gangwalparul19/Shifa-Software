package com.shifa.oms.order.dto;

import com.shifa.oms.order.OrderPaymentScreenshot;

import java.time.LocalDateTime;

/**
 * One payment proof attached to an order (V65), as listed by
 * {@code GET /api/orders/{id}/payment-screenshots}.
 *
 * <p>Carries only metadata, never the bytes — the viewer renders each proof by
 * calling {@code GET /api/orders/{id}/payment-screenshots/{screenshotId}}, which
 * streams the image inline. The opaque storage key is deliberately NOT exposed:
 * it is an internal reference to the object store and clients address a proof by
 * its id.
 *
 * @param id          the proof's identifier, used to fetch its bytes
 * @param filename    client-reported filename, or null when not captured
 *                    (historical proofs backfilled from the legacy single column)
 * @param contentType client-reported MIME type, or null when not captured
 * @param byteSize    size in bytes, or null when not captured
 * @param primary     whether this is the order's first/primary proof — the one
 *                    also served by the legacy
 *                    {@code GET /api/orders/{id}/payment-screenshot} endpoint
 * @param createdAt   when the proof was attached to the order
 */
public record PaymentScreenshotResponse(
        Long id,
        String filename,
        String contentType,
        Long byteSize,
        boolean primary,
        LocalDateTime createdAt
) {

    public static PaymentScreenshotResponse from(OrderPaymentScreenshot screenshot) {
        return new PaymentScreenshotResponse(
                screenshot.getId(),
                screenshot.getFilename(),
                screenshot.getContentType(),
                screenshot.getByteSize(),
                screenshot.getSortOrder() == 0,
                screenshot.getCreatedAt());
    }
}
