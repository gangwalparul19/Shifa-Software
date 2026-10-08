package com.shifa.oms.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.settings.SettingsService;
import com.shifa.oms.shopify.dto.ShopifyOrderPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * Shopify {@code orders/create} webhook receiver: mirrors each order placed on the
 * Shopify storefront into this OMS automatically (Shopify integration).
 *
 * <p>Unauthenticated to the browser (permitted in {@code SecurityConfig} under
 * {@code /api/webhooks/**}) but validated by the Shopify HMAC-SHA256 signature over
 * the raw body ({@link ShopifyHmacVerifier}, base64 in {@code X-Shopify-Hmac-Sha256});
 * an invalid signature returns 401 so a forged request is rejected. The handler
 * consumes the raw body so the signature is checked against exactly the received
 * bytes, then maps the payload to an order via {@link ShopifyOrderImportService}.
 *
 * <p>Processing is idempotent: Shopify redelivers a webhook on any non-2xx response
 * or timeout, and the import service keys off the Shopify order id so a redelivery
 * of the same order is recognised and never duplicated. The handler always returns
 * 200 for a genuine, parseable Shopify order (created or duplicate) so Shopify stops
 * retrying.
 */
@RestController
@RequestMapping("/api/webhooks/shopify")
public class ShopifyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookController.class);

    /** Header carrying the base64 HMAC-SHA256 signature of the raw body (Shopify standard). */
    public static final String SIGNATURE_HEADER = "X-Shopify-Hmac-Sha256";

    private final ShopifyHmacVerifier signatureVerifier;
    private final ShopifyOrderImportService importService;
    private final ObjectMapper objectMapper;
    private final SettingsService settingsService;

    public ShopifyWebhookController(ShopifyHmacVerifier signatureVerifier,
                                    ShopifyOrderImportService importService,
                                    ObjectMapper objectMapper,
                                    SettingsService settingsService) {
        this.signatureVerifier = signatureVerifier;
        this.importService = importService;
        this.objectMapper = objectMapper;
        this.settingsService = settingsService;
    }

    /**
     * Receive a Shopify order-creation webhook (HMAC-validated, idempotent) and
     * mirror it into an OMS order.
     */
    @PostMapping("/orders")
    public Map<String, Object> receiveOrder(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "The Shopify webhook signature is invalid.");
        }
        ShopifyOrderPayload payload = parse(rawBody);
        if (payload.id() == null) {
            throw new ValidationException("A Shopify order webhook must include an order id.");
        }
        // Integration switch (Shopify Sync page). When OFF the order is NOT imported,
        // but we still answer 200 so Shopify neither retries nor auto-disables the
        // webhook after repeated failures. Orders received while OFF are not
        // replayed later — Shopify only redelivers non-2xx responses.
        if (!settingsService.isShopifySyncEnabled()) {
            log.info("Shopify integration is OFF — ignored order {} ({}).", payload.id(), payload.name());
            return Map.of("ignored", true, "reason", "SHOPIFY_SYNC_DISABLED");
        }
        ShopifyOrderImportService.ImportResult result = importService.importOrder(payload);
        return Map.of(
                "orderCode", result.orderCode(),
                "created", result.created());
    }

    private ShopifyOrderPayload parse(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, ShopifyOrderPayload.class);
        } catch (IOException e) {
            throw new ValidationException("The Shopify webhook body is not valid JSON.");
        }
    }
}
