package com.shifa.oms.courier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.common.ApiException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.courier.dto.CourierWebhookRequest;
import com.shifa.oms.courier.dto.CourierWebhookResponse;
import com.shifa.oms.statemachine.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Optional;

/**
 * Courier tracking webhook (Req 13.1, 13.2, 17.1).
 *
 * <p>Unauthenticated to the browser (permitted in {@link com.shifa.oms.auth.SecurityConfig})
 * but validated by an HMAC-SHA256 signature over the raw body
 * ({@link HmacSignatureVerifier}); an invalid signature returns 401. The handler
 * consumes the raw body so the signature is checked against exactly the received
 * bytes, then maps and applies the status via {@link CourierStatusApplier}.
 *
 * <p>Processing is idempotent and tolerant of duplicates/out-of-order updates:
 * an update is applied only when the transition is legal from the order's current
 * status, otherwise it is accepted with {@code applied=false}.
 */
@RestController
@RequestMapping("/api/webhooks/courier")
public class CourierWebhookController {

    private static final Logger log = LoggerFactory.getLogger(CourierWebhookController.class);

    /** Header carrying the hex HMAC-SHA256 signature of the raw body. */
    public static final String SIGNATURE_HEADER = "X-Courier-Signature";

    private final HmacSignatureVerifier signatureVerifier;
    private final CourierStatusApplier statusApplier;
    private final ObjectMapper objectMapper;

    public CourierWebhookController(HmacSignatureVerifier signatureVerifier,
                                    CourierStatusApplier statusApplier,
                                    ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.statusApplier = statusApplier;
        this.objectMapper = objectMapper;
    }

    /** Receive a courier tracking update (HMAC-validated, idempotent). */
    @PostMapping
    public CourierWebhookResponse receive(@RequestBody byte[] rawBody,
                                          @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE",
                    "The courier webhook signature is invalid.");
        }
        CourierWebhookRequest request = parse(rawBody);
        if (request.awb() == null || request.awb().isBlank()) {
            throw new ValidationException("A courier webhook must include an AWB.");
        }
        if (request.status() == null || request.status().isBlank()) {
            throw new ValidationException("A courier webhook must include a status.");
        }

        Optional<OrderStatus> applied = statusApplier.applyByAwb(request.awb().trim(), request.status().trim());
        return new CourierWebhookResponse(
                applied.isPresent(),
                applied.map(Enum::name).orElse(null));
    }

    private CourierWebhookRequest parse(byte[] rawBody) {
        try {
            return objectMapper.readValue(rawBody, CourierWebhookRequest.class);
        } catch (IOException e) {
            throw new ValidationException("The courier webhook body is not valid JSON.");
        }
    }
}
