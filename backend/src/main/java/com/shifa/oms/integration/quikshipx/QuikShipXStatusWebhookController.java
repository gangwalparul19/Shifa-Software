package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

/**
 * Receives QuikShipX status updates and mirrors them onto the order
 * (spec {@code shopify-quikshipx-order-sync}, Req 6).
 *
 * <p>Mounted under {@code /api/webhooks/**}, which {@code SecurityConfig} already permits
 * without a JWT — QuikShipX is an external caller. This is the "going forward, any status
 * transition on their end reflects in our portal" path: the moment QuikShipX POSTs a status
 * here, it appears on the order.
 *
 * <p>Because QuikShipX has not published a webhook contract, the parser is <b>tolerant</b>:
 * it accepts the identifier under any of the names QuikShipX uses elsewhere
 * ({@code order_id}, {@code id}, {@code customer_order_id}) and the status under several
 * likely names, and it reads the body raw so a signature (if configured) is checked against
 * exactly the received bytes. When the exact format is known it can be tightened without a
 * client change.
 *
 * <p>Signature is optional: if {@code app.quikshipx.webhook-secret} is set, a valid
 * {@code X-QuikShipX-Signature} (hex HMAC-SHA256 of the body) is required; if no secret is
 * configured, updates are accepted so the flow can be exercised before QuikShipX finalises
 * webhook auth. A status mirror is low-risk (it changes a display string, never money or the
 * internal state machine), which is why this is safe to leave open in that mode.
 */
@RestController
@RequestMapping("/api/webhooks/quikshipx")
public class QuikShipXStatusWebhookController {

    private static final Logger log = LoggerFactory.getLogger(QuikShipXStatusWebhookController.class);
    private static final String HMAC_SHA256 = "HmacSHA256";

    /** Header carrying the hex HMAC-SHA256 of the raw body, when a secret is configured. */
    public static final String SIGNATURE_HEADER = "X-QuikShipX-Signature";

    private final QuikShipXStatusUpdateService statusUpdateService;
    private final QuikShipXProperties properties;
    private final ObjectMapper objectMapper;

    public QuikShipXStatusWebhookController(QuikShipXStatusUpdateService statusUpdateService,
                                            QuikShipXProperties properties,
                                            ObjectMapper objectMapper) {
        this.statusUpdateService = statusUpdateService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** The webhook response body — enough for an operator to see what happened. */
    public record StatusAck(boolean applied, String outcome, Long orderId, String status) {
    }

    @PostMapping("/status")
    public StatusAck receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {

        byte[] body = rawBody == null ? new byte[0] : rawBody;
        verifySignature(body, signature);

        JsonNode root = parse(body);
        String status = firstText(root, "status", "order_status", "current_status",
                "status_name", "shipment_status");
        String awb = firstText(root, "awb", "awb_number", "awbNumber", "waybill",
                "waybill_number", "tracking_number", "trackingNumber");
        String quikshipxOrderId = firstText(root, "order_id", "orderId", "order_number", "orderNumber");
        String shipmentId = firstText(root, "id", "shipment_id", "shipmentId");
        String reference = firstText(root, "customer_order_id", "customerOrderId",
                "order_reference", "reference");
        LocalDateTime at = parseTimestamp(firstText(root, "status_time", "statusTime",
                "timestamp", "updated_at", "event_time"));

        QuikShipXStatusUpdateService.Result result = statusUpdateService.apply(
                quikshipxOrderId, shipmentId, reference, status, awb, at);

        if (result.outcome() == QuikShipXStatusUpdateService.Outcome.UNKNOWN_SHIPMENT) {
            // Acknowledge with 202 rather than erroring: retrying will not help if the order
            // is not ours, and a 5xx would make QuikShipX hammer the endpoint.
            log.warn("QuikShipX status webhook did not match any order: {}", result);
        }
        return new StatusAck(result.applied(), result.outcome().name(), result.orderId(), result.status());
    }

    private void verifySignature(byte[] body, String signature) {
        if (!properties.hasWebhookSecret()) {
            return; // No secret configured — accept (pre-contract testing mode).
        }
        String expected = hexHmac(body, properties.webhookSecret());
        boolean ok = signature != null && !signature.isBlank()
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        signature.trim().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "The QuikShipX status webhook signature is invalid.");
        }
    }

    private static String hexHmac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute the QuikShipX HMAC", e);
        }
    }

    private JsonNode parse(byte[] body) {
        if (body.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_BODY",
                    "The QuikShipX status webhook body was empty.");
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_JSON",
                    "The QuikShipX status webhook body was not valid JSON.");
        }
    }

    /** First non-blank string among the given field names, searched at the top level. */
    private static String firstText(JsonNode root, String... fields) {
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = root.get(field);
            if (value != null && value.isValueNode() && !value.isNull()) {
                String text = value.asText();
                if (text != null && !text.isBlank()) {
                    return text.trim();
                }
            }
        }
        return null;
    }

    /** Parses an ISO-8601 timestamp when present; null (meaning "now") otherwise. */
    private static LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.trim()).toLocalDateTime();
        } catch (DateTimeParseException offsetMiss) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (DateTimeParseException localMiss) {
                return null;
            }
        }
    }
}
