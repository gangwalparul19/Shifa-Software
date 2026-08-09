package com.shifa.oms.integration.meta;

import com.shifa.oms.common.ApiException;
import com.shifa.oms.integration.WebhookPayloadLimit;
import com.shifa.oms.integration.meta.MetaLeadIngestService.ReceiveResult;
import com.shifa.oms.integration.meta.dto.MetaWebhookAck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 * Receives Meta (Facebook/Instagram) Lead Ads webhooks (spec {@code meta-lead-sync},
 * Req 1, 2, 3).
 *
 * <p>Mounted under {@code /api/webhooks/**}, which {@code SecurityConfig} already
 * permits without a JWT — the same placement the Shopify, courier and WhatsApp
 * webhooks use, so no security configuration change. Authentication for the POST is
 * the HMAC signature; the GET handshake is authenticated by the verify token.
 *
 * <ul>
 *   <li>{@code GET} — the one-time subscription verification handshake (Req 1). Meta
 *       sends {@code hub.mode=subscribe}, {@code hub.verify_token}, {@code hub.challenge};
 *       a matching token echoes the challenge, a mismatch is 403, missing params 400.</li>
 *   <li>{@code POST} — a lead notification (Req 2, 3). Size check → constant-time HMAC
 *       verify (401 on failure, nothing stored) → store each entry + enqueue ingestion
 *       in one transaction → 200. Mapping/fetch/capture happen off the request thread.</li>
 * </ul>
 *
 * <p>The POST body is read as {@code byte[]} because the signature covers the exact
 * received bytes; letting Jackson re-serialise it would change whitespace and
 * invalidate the HMAC.
 */
@RestController
@RequestMapping("/api/webhooks/meta")
public class MetaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(MetaWebhookController.class);
    private static final String MODE_SUBSCRIBE = "subscribe";

    private final MetaProperties properties;
    private final MetaWebhookVerifier verifier;
    private final MetaLeadIngestService ingestService;

    public MetaWebhookController(MetaProperties properties,
                                 MetaWebhookVerifier verifier,
                                 MetaLeadIngestService ingestService) {
        this.properties = properties;
        this.verifier = verifier;
        this.ingestService = ingestService;
    }

    /**
     * Subscription verification handshake (Req 1). Returns the challenge verbatim when
     * the mode is {@code subscribe} and the verify token matches the configured value.
     */
    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String verifyToken,
            @RequestParam(value = "hub.challenge", required = false) String challenge) {

        if (mode == null || verifyToken == null || challenge == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_VERIFICATION",
                    "hub.mode, hub.verify_token and hub.challenge are all required.");
        }
        if (!MODE_SUBSCRIBE.equals(mode) || !tokenMatches(verifyToken)) {
            log.warn("Rejected a Meta webhook verification with a bad mode/token at {}", LocalDateTime.now());
            throw new ApiException(HttpStatus.FORBIDDEN, "VERIFICATION_FAILED",
                    "The verification token did not match.");
        }
        return ResponseEntity.ok(challenge);
    }

    /**
     * Accepts one lead notification (Req 2, 3).
     *
     * @param rawBody       the exact bytes received
     * @param signature     Meta's {@code sha256=<hex>} HMAC-SHA256 of {@code rawBody}
     * @param contentLength the declared body size, used to reject before hashing
     */
    @PostMapping
    public MetaWebhookAck receive(
            @RequestBody(required = false) byte[] rawBody,
            @RequestHeader(value = MetaProperties.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = HttpHeaders.CONTENT_LENGTH, required = false) Long contentLength) {

        byte[] body = rawBody == null ? new byte[0] : rawBody;

        // (1) Size, before hashing anything.
        if (WebhookPayloadLimit.verdict(contentLength, body.length, properties.maxPayloadBytes())
                == WebhookPayloadLimit.Verdict.TOO_LARGE) {
            log.warn("Rejected an oversized Meta webhook delivery: {} byte(s), limit {}",
                    body.length, WebhookPayloadLimit.clampMax(properties.maxPayloadBytes()));
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "The Meta webhook body exceeds the permitted size.");
        }

        // (2) Signature. Nothing is persisted for a rejected delivery.
        if (!verifier.isValid(body, signature)) {
            log.warn("Rejected an unverified Meta webhook delivery at {}", LocalDateTime.now());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "The Meta webhook signature is invalid.");
        }

        // (3) Store + enqueue in one transaction, then acknowledge.
        ReceiveResult result = ingestService.receive(body);
        log.info("Meta webhook accepted: {} entr(ies), {} queued, {} duplicate(s)",
                result.received(), result.queued(), result.duplicates());
        return MetaWebhookAck.of(result.received(), result.queued(), result.duplicates());
    }

    /**
     * Constant-time comparison of the presented verify token against the configured
     * one; a blank configured token never matches (the handshake cannot be completed
     * until it is set).
     */
    private boolean tokenMatches(String presented) {
        if (!properties.hasVerifyToken() || presented == null) {
            return false;
        }
        byte[] expected = properties.verifyToken().getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, actual);
    }
}
