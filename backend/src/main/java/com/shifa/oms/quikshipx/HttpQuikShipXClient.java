package com.shifa.oms.quikshipx;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.quikshipx.QuikShipXModels.AllotResult;
import com.shifa.oms.quikshipx.QuikShipXModels.CreatePayload;
import com.shifa.oms.quikshipx.QuikShipXModels.CreateResult;
import com.shifa.oms.quikshipx.QuikShipXModels.TrackResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live {@link QuikShipXClient} over the QuikShipX v1 API, active when
 * {@code app.quikshipx.mode=HTTP}.
 *
 * <p>Two things differ from a conventional REST client:
 * <ul>
 *   <li><b>No authorization header.</b> QuikShipX authenticates from
 *       {@code shipper_details} in the body, so the only header is
 *       {@code Content-Type: application/json}.</li>
 *   <li><b>Tolerant success handling.</b> QuikShipX returns HTTP 200 even when it
 *       rejects the request (a {@code {"status":"failure","errors":[...]}} body),
 *       so a 2xx is inspected for a soft failure before being treated as success.</li>
 * </ul>
 *
 * <p>Failure classification decides whether a retry could help: transport errors,
 * timeouts, 408/429/5xx and "not ready / not found" soft failures are retryable;
 * other rejections (bad address/HSN) are permanent.
 */
@Component
@ConditionalOnProperty(name = "app.quikshipx.mode", havingValue = "HTTP")
public class HttpQuikShipXClient implements QuikShipXClient {

    private static final Logger log = LoggerFactory.getLogger(HttpQuikShipXClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final QuikShipXProperties properties;
    private final HttpClient httpClient;

    public HttpQuikShipXClient(QuikShipXProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    @Override
    public CreateResult createOrder(CreatePayload payload) throws QuikShipXException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer_details", payload.customerDetails());
        body.put("shipment_details", payload.shipmentDetails());
        body.put("product_details", payload.productDetails());
        body.put("shipper_details", shipperDetails());

        String responseBody = post(properties.createOrderUrl(), body, "create-order");
        List<String> soft = QuikShipXResponseParser.detectFailure(responseBody);
        if (!soft.isEmpty()) {
            boolean duplicate = soft.stream().anyMatch(s -> s.toLowerCase().contains("exist")
                    || s.toLowerCase().contains("duplicate"));
            // A duplicate reference means QuikShipX already has this order — not a
            // real failure. A bad field is permanent (fails the same way next time).
            throw new QuikShipXException("QuikShipX create-order rejected order "
                    + payload.orderCode() + ": " + String.join("; ", soft), duplicate);
        }
        log.info("QuikShipX create-order accepted orderCode={}", payload.orderCode());
        return QuikShipXResponseParser.parseCreate(responseBody);
    }

    @Override
    public AllotResult allotTrackingId(String shipperOrderId) throws QuikShipXException {
        Map<String, Object> orderDetails = new LinkedHashMap<>();
        orderDetails.put("id_value", shipperOrderId);
        orderDetails.put("id_type", "shipper_order_id");
        orderDetails.put("courier_id", properties.courierId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("order_details", orderDetails);
        body.put("shipper_details", shipperDetails());

        String responseBody = post(properties.allotTrackingUrl(), body, "allot-tracking-id");
        List<String> soft = QuikShipXResponseParser.detectFailure(responseBody);
        if (!soft.isEmpty()) {
            // "Shipment Not Found" here means create hasn't propagated yet — retryable.
            throw new QuikShipXException("QuikShipX allot-tracking-id not ready for "
                    + shipperOrderId + ": " + String.join("; ", soft), true);
        }
        try {
            AllotResult result = QuikShipXResponseParser.parseAllot(responseBody);
            if (result.awb() == null || result.awb().isBlank()) {
                throw new QuikShipXException(
                        "QuikShipX allot-tracking-id returned no tracking id for " + shipperOrderId, true);
            }
            return result;
        } catch (QuikShipXResponseParser.MalformedResponse e) {
            throw new QuikShipXException(e.getMessage(), false, e);
        }
    }

    @Override
    public TrackResult trackOrder(String awb) throws QuikShipXException {
        Map<String, Object> trackingDetails = new LinkedHashMap<>();
        trackingDetails.put("tracking_no", awb);
        trackingDetails.put("tracking_type", "awb");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tracking_details", trackingDetails);
        body.put("shipper_details", shipperDetails());

        String responseBody = post(properties.trackOrderUrl(), body, "track-order");
        List<String> soft = QuikShipXResponseParser.detectFailure(responseBody);
        if (!soft.isEmpty()) {
            // Not yet trackable (e.g. between booking and tracking-id) — retryable.
            throw new QuikShipXException("QuikShipX track-order not ready for AWB "
                    + awb + ": " + String.join("; ", soft), true);
        }
        try {
            return QuikShipXResponseParser.parseTrack(responseBody);
        } catch (QuikShipXResponseParser.MalformedResponse e) {
            throw new QuikShipXException(e.getMessage(), false, e);
        }
    }

    /** The shipper_details credentials block (never logged — carries the secret). */
    private Map<String, Object> shipperDetails() {
        Map<String, Object> shipper = new LinkedHashMap<>();
        shipper.put("client_code", nullToEmpty(properties.clientCode()));
        shipper.put("user_id", nullToEmpty(properties.userId()));
        shipper.put("user_secret", nullToEmpty(properties.activeSecret()));
        return shipper;
    }

    /** POSTs a JSON body and returns the response body, classifying failures. */
    private String post(String url, Map<String, Object> body, String op) throws QuikShipXException {
        String json;
        try {
            json = MAPPER.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new QuikShipXException("QuikShipX " + op + " could not serialise the request", false, e);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.requestTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new QuikShipXException("QuikShipX " + op + " timed out after "
                    + properties.requestTimeout(), true, e);
        } catch (IOException e) {
            throw new QuikShipXException("QuikShipX " + op + " failed to connect: " + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QuikShipXException("QuikShipX " + op + " was interrupted", true, e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        boolean retryable = status == 408 || status == 429 || status >= 500;
        log.warn("QuikShipX {} returned HTTP {} (retryable={})", op, status, retryable);
        throw new QuikShipXException("QuikShipX " + op + " returned HTTP " + status, retryable);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
