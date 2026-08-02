package com.shifa.oms.integration.shopify;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.integration.IntegrationEvent;
import com.shifa.oms.integration.IntegrationEventStore;
import com.shifa.oms.integration.IntegrationOutcome;
import com.shifa.oms.integration.IntegrationSource;
import com.shifa.oms.integration.MalformedPayloadException;
import com.shifa.oms.integration.WebhookPayloadLimit;
import com.shifa.oms.integration.shopify.dto.ShopifyWebhookAck;
import com.shifa.oms.platform.outbox.OutboxEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Receives Shopify order webhooks (Req 2.1&ndash;2.8).
 *
 * <p>Mounted under {@code /api/webhooks/**}, which {@code SecurityConfig} already permits
 * without a JWT — the same placement the courier and WhatsApp webhooks use, so no security
 * configuration changes for this feature. Authentication is the HMAC signature, nothing else.
 *
 * <p>The handler does as little as possible, in a fixed order, because Shopify expects a
 * response inside 5 seconds and retries anything slower:
 *
 * <ol>
 *   <li><b>Size check first</b>, before the HMAC. Hashing a body that is about to be
 *       rejected is wasted work and a cheap denial-of-service lever (Req 2.8 &rarr; 413).</li>
 *   <li><b>Constant-time signature verification</b> (Req 2.2, 2.3 &rarr; 401). Nothing is
 *       persisted for a rejected delivery, not even the payload.</li>
 *   <li><b>Store the raw delivery</b> keyed by Shopify's webhook id. The unique constraint
 *       makes a repeat delivery a no-op (Req 2.6).</li>
 *   <li><b>Enqueue ingestion</b> and return 200. Mapping, product matching and the approval
 *       transition all happen off the request thread (Req 2.5).</li>
 * </ol>
 *
 * <p>The body is taken as {@code byte[]}: the signature covers the exact received bytes, so
 * letting Jackson parse and re-serialise it would change whitespace and invalidate the HMAC.
 */
@RestController
@RequestMapping("/api/webhooks/shopify")
public class ShopifyWebhookController {

    private static final Logger log = LoggerFactory.getLogger(ShopifyWebhookController.class);

    /** Prefix for the synthetic event id used when Shopify sends no webhook id header. */
    private static final String BODY_DIGEST_PREFIX = "body-sha256:";

    private final ShopifyProperties properties;
    private final ShopifyWebhookVerifier verifier;
    private final IntegrationEventStore eventStore;
    private final OutboxEventPublisher outboxEventPublisher;

    public ShopifyWebhookController(ShopifyProperties properties,
                                    ShopifyWebhookVerifier verifier,
                                    IntegrationEventStore eventStore,
                                    OutboxEventPublisher outboxEventPublisher) {
        this.properties = properties;
        this.verifier = verifier;
        this.eventStore = eventStore;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    /**
     * Accepts one {@code orders/create} (or {@code orders/updated}) delivery.
     *
     * @param rawBody       the exact bytes received
     * @param signature     Shopify's base64 HMAC-SHA256 of {@code rawBody}
     * @param webhookId     Shopify's unique delivery id, the duplicate-detection key
     * @param topic         the event topic, recorded for context
     * @param contentLength the declared body size, used to reject before reading further
     */
    @PostMapping("/orders")
    @Transactional
    public ShopifyWebhookAck receiveOrder(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = ShopifyProperties.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = ShopifyProperties.EVENT_ID_HEADER, required = false) String webhookId,
            @RequestHeader(value = ShopifyProperties.TOPIC_HEADER, required = false) String topic,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) {

        LocalDateTime receivedAt = LocalDateTime.now();
        byte[] body = rawBody == null ? new byte[0] : rawBody;

        // (1) Size, before hashing anything (Req 2.8).
        if (WebhookPayloadLimit.verdict(contentLength, body.length, properties.maxPayloadBytes())
                == WebhookPayloadLimit.Verdict.TOO_LARGE) {
            log.warn("Rejected an oversized Shopify webhook delivery at {}: {} byte(s), limit {}",
                    receivedAt, body.length, WebhookPayloadLimit.clampMax(properties.maxPayloadBytes()));
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "The Shopify webhook body exceeds the permitted size.");
        }

        // (2) Signature. Nothing is persisted for a rejected delivery — logging the
        // source and time is deliberate, storing an unauthenticated payload is not
        // (Req 2.3).
        if (!verifier.isValid(body, signature)) {
            log.warn("Rejected an unverified Shopify webhook delivery from source SHOPIFY at {}", receivedAt);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "The Shopify webhook signature is invalid.");
        }

        String payload = new String(body, StandardCharsets.UTF_8);
        String eventId = eventIdOf(webhookId, body);

        // (3) Store. An existing (source, external_event_id) yields empty, which is the
        // duplicate case: acknowledge and stop (Req 2.6).
        Optional<IntegrationEvent> stored = eventStore.record(
                IntegrationSource.SHOPIFY, eventId, topicOf(topic), payload);
        if (stored.isEmpty()) {
            log.info("Shopify webhook {} was already received; acknowledging without reprocessing", eventId);
            return ShopifyWebhookAck.ofDuplicate();
        }

        IntegrationEvent event = stored.get();
        Long storedId = event.getId();

        // A body that cannot even yield an order id will fail identically on every
        // attempt, so it is settled here rather than burning the retry ladder (Req 8.7).
        String shopifyOrderId;
        try {
            shopifyOrderId = ShopifyOrderPayloadCodec.parse(payload).shopifyOrderId();
        } catch (MalformedPayloadException malformed) {
            eventStore.markOutcome(storedId, IntegrationOutcome.MALFORMED_PAYLOAD, malformed.getMessage());
            log.warn("Shopify webhook {} carried an unusable body: {}", eventId, malformed.getMessage());
            return ShopifyWebhookAck.ofStoredOnly(storedId,
                    "Stored for review; the body could not be read: " + malformed.getMessage());
        }

        // (4) Hand off. The outbox row commits with this request, so an accepted
        // delivery can never be acknowledged without its ingestion being queued.
        outboxEventPublisher.publishShopifyOrderIngest(storedId, shopifyOrderId);
        log.info("Stored Shopify webhook {} (order {}) as integration event {} and queued ingestion",
                eventId, shopifyOrderId, storedId);
        return ShopifyWebhookAck.ofQueued(storedId);
    }

    /**
     * The duplicate-detection key. Shopify always sends {@code X-Shopify-Webhook-Id}, but
     * falling back to a digest of the body keeps idempotence rather than losing it: a
     * retried delivery carries identical bytes, so it produces the same key.
     */
    private static String eventIdOf(String webhookId, byte[] body) {
        if (webhookId != null && !webhookId.isBlank()) {
            return webhookId.trim();
        }
        return BODY_DIGEST_PREFIX + sha256Hex(body);
    }

    private static String sha256Hex(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String topicOf(String topic) {
        return topic == null || topic.isBlank() ? "orders/create" : topic.trim();
    }
}
