package com.shifa.oms.common;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a change is requested to an order whose content an external storefront
 * owns (spec {@code shopify-quikshipx-order-sync}, Req 9.4, 9.5). Mapped to HTTP 409;
 * the stored order is left unchanged.
 *
 * <p>409 rather than 403 on purpose: the caller is not lacking a permission — no role has
 * this permission — the change simply belongs in the system that owns the order.
 */
public class ExternalOrderReadOnlyException extends ApiException {

    public ExternalOrderReadOnlyException(String message) {
        super(HttpStatus.CONFLICT, "EXTERNAL_ORDER_READ_ONLY", message);
    }
}
