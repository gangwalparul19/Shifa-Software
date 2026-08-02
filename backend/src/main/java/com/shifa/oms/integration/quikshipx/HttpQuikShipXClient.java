package com.shifa.oms.integration.quikshipx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Live {@link QuikShipXClient} over {@code POST {base-url}/api/create-order-v1}
 * (contract: {@code docs/QUIKSHIPX-API-V1.md}), active when
 * {@code app.quikshipx.mode=HTTP}.
 *
 * <p>Two points where this differs from a conventional REST client:
 *
 * <ul>
 *   <li><b>No authorization header.</b> QuikShipX authenticates from
 *       {@code shipper_details} inside the body, so the only header is
 *       {@code Content-Type: application/json}.</li>
 *   <li><b>Tolerant response handling.</b> The response body is undocumented, so a 2xx
 *       is an acceptance regardless of what it contains, and identifier extraction is
 *       best-effort against configured candidate keys.</li>
 * </ul>
 *
 * <p>Failure classification decides whether the retry ladder runs: transport errors,
 * timeouts, 408, 429 and 5xx are retryable; other 4xx are permanent, because a body
 * QuikShipX considers invalid will be just as invalid in eight minutes.
 */
@Component
@ConditionalOnProperty(name = "app.quikshipx.mode", havingValue = "HTTP")
public class HttpQuikShipXClient implements QuikShipXClient {

    private static final Logger log = LoggerFactory.getLogger(HttpQuikShipXClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Keys a provider might use to report which fields it rejected. */
    private static final List<String> ERROR_KEYS =
            List.of("errors", "error", "message", "messages", "detail", "details");

    private final QuikShipXProperties properties;
    private final HttpClient httpClient;

    public HttpQuikShipXClient(QuikShipXProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    @Override
    public ShipmentAcceptance createShipment(ShipmentSubmission submission) throws QuikShipXClientException {
        String url = properties.createOrderUrl();
        String body = QuikShipXSubmissionCodec.serialize(submission);
        String reference = submission.orderReference();

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new QuikShipXClientException(
                    "QuikShipX create-order timed out after " + properties.requestTimeout(), true, e);
        } catch (IOException e) {
            throw new QuikShipXClientException(
                    "QuikShipX create-order failed to connect: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            // Restore the flag so a shutdown is not swallowed.
            Thread.currentThread().interrupt();
            throw new QuikShipXClientException("QuikShipX create-order was interrupted", true, e);
        }

        int status = response.statusCode();
        String responseBody = response.body();

        if (status >= 200 && status < 300) {
            // QuikShipX returns 200 even when it rejects the order, carrying
            // {"status":"failure","errors":[...]} in the body (e.g. an invalid HSN code).
            // Detect that soft failure so we do not record a phantom shipment for an order
            // the courier never actually created.
            List<String> softErrors = QuikShipXAcceptanceCodec.detectFailure(responseBody);
            if (!softErrors.isEmpty()) {
                boolean alreadyExists = QuikShipXAcceptanceCodec.isDuplicateReference(softErrors);
                if (alreadyExists) {
                    // Not a failure: QuikShipX already has this customer_order_id. This
                    // happens when a concurrent auto-publish won the race, or the order was
                    // sent before. The publisher treats it as idempotent "already published".
                    log.info("QuikShipX reports order reference={} already exists; treating as "
                            + "already published", reference);
                } else {
                    log.warn("QuikShipX create-order returned HTTP {} but the body reported failure "
                            + "reference={} errors={}", status, reference, softErrors);
                }
                // Not retryable: a bad HSN/address fails identically next time, and a
                // duplicate stays a duplicate.
                throw new QuikShipXClientException(
                        (alreadyExists ? "QuikShipX already has this order: " : "QuikShipX rejected the order: ")
                                + String.join("; ", softErrors),
                        false, status, softErrors, null, alreadyExists);
            }
            // Note: NOT logging the request body — it carries the user secret.
            log.info("QuikShipX create-order accepted reference={} status={}", reference, status);
            return QuikShipXAcceptanceCodec.parse(
                    reference, properties.isTestSecret(), responseBody, properties.responseKeys());
        }

        boolean retryable = status == 408 || status == 429 || status >= 500;
        List<String> rejectedFields = extractRejectedFields(responseBody);
        log.warn("QuikShipX create-order rejected reference={} status={} retryable={} fields={}",
                reference, status, retryable, rejectedFields);
        throw new QuikShipXClientException(
                "QuikShipX create-order returned HTTP " + status
                        + (rejectedFields.isEmpty() ? "" : " rejecting " + String.join(", ", rejectedFields)),
                retryable, status, rejectedFields, null);
    }

    @Override
    public QuikShipXStatusEvent fetchStatus(String shipmentReference) throws QuikShipXClientException {
        // Intentionally unimplemented: the supplied contract defines no status-query
        // operation. Filling in a guessed path would produce silent wrong statuses,
        // which is worse than not mirroring status at all.
        throw new QuikShipXClientException(
                "QuikShipX exposes no documented status-query operation; "
                        + "supply the endpoint before enabling app.quikshipx.status-feed-available.",
                false);
    }

    /**
     * Best-effort extraction of the field names an error body complains about, so the
     * health console can show something actionable.
     */
    private List<String> extractRejectedFields(String body) {
        List<String> fields = new ArrayList<>();
        if (body == null || body.isBlank()) {
            return fields;
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            for (String key : ERROR_KEYS) {
                JsonNode node = root.get(key);
                if (node == null) {
                    continue;
                }
                if (node.isObject()) {
                    // Shape {"errors": {"customer_pincode": "required"}} — keys are fields.
                    for (Map.Entry<String, JsonNode> entry : node.properties()) {
                        fields.add(entry.getKey());
                    }
                } else if (node.isArray()) {
                    node.forEach(item -> fields.add(item.asText()));
                } else if (node.isValueNode()) {
                    fields.add(node.asText());
                }
                if (!fields.isEmpty()) {
                    return fields;
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // A non-JSON error body tells us nothing about fields; that is fine.
            log.debug("QuikShipX error body was not JSON");
        }
        return fields;
    }
}
