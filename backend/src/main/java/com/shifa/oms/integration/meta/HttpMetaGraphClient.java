package com.shifa.oms.integration.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.integration.meta.dto.MetaField;
import com.shifa.oms.integration.meta.dto.MetaLeadData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Live {@link MetaGraphClient} over the Meta Graph API (spec {@code meta-lead-sync},
 * Req 5, 10), using the JDK {@link HttpClient} like {@code HttpQuikShipXClient}.
 *
 * <p>The Page access token authenticates every call and is passed as the
 * {@code access_token} query parameter. It is <b>never</b> logged (Req 9.4) — the
 * request URL is not logged, only the leadgen/page id and status.
 *
 * <p>Failure classification (Req 5.3, 5.4): timeouts, transport errors, HTTP 429 and
 * 5xx are retryable; a 4xx (typically an {@code OAuthException} / code 190 for an
 * invalid or expired token) is a non-retryable authorization failure.
 *
 * <p>Active only when {@code app.meta.mode=HTTP}; local development uses
 * {@link MockMetaGraphClient} so no token or network is required.
 */
@Component
@ConditionalOnProperty(name = "app.meta.mode", havingValue = "HTTP")
public class HttpMetaGraphClient implements MetaGraphClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMetaGraphClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetaProperties properties;
    private final HttpClient httpClient;

    public HttpMetaGraphClient(MetaProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.requestTimeout())
                .build();
    }

    @Override
    public MetaLeadData fetchLead(String leadgenId) {
        String url = properties.graphUrl(enc(leadgenId))
                + "?fields=" + enc("field_data,created_time,form_id,ad_name,campaign_name")
                + "&access_token=" + enc(token());
        JsonNode root = get(url, "lead " + leadgenId);

        List<MetaField> fields = new ArrayList<>();
        JsonNode fieldData = root.path("field_data");
        if (fieldData.isArray()) {
            for (JsonNode entry : fieldData) {
                String name = entry.path("name").asText(null);
                if (name == null || name.isBlank()) {
                    continue;
                }
                fields.add(new MetaField(name.trim(), firstValue(entry.path("values"))));
            }
        }
        String formName = root.path("ad_name").asText(null);
        if (formName == null || formName.isBlank()) {
            formName = root.path("campaign_name").asText(null);
        }
        return new MetaLeadData(leadgenId, blankToNull(formName), fields);
    }

    @Override
    public String fetchPageName() {
        String url = properties.graphUrl(enc(properties.resolvedPageId()))
                + "?fields=name&access_token=" + enc(token());
        JsonNode root = get(url, "page " + properties.resolvedPageId());
        String name = root.path("name").asText(null);
        if (name == null || name.isBlank()) {
            throw new MetaGraphException("Meta returned no page name for the configured page id", false);
        }
        return name;
    }

    /** Executes a GET and returns the parsed JSON, classifying failures. */
    private JsonNode get(String url, String what) {
        if (!properties.hasPageAccessToken()) {
            throw new MetaGraphException(
                    "Meta page access token is not configured (app.meta.page-access-token)", false);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(properties.requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new MetaGraphException("Meta Graph request for " + what + " timed out after "
                    + properties.requestTimeout(), true, e);
        } catch (IOException e) {
            throw new MetaGraphException("Meta Graph request for " + what + " failed to connect: "
                    + e.getMessage(), true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MetaGraphException("Meta Graph request for " + what + " was interrupted", true, e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return MAPPER.readTree(response.body());
            } catch (IOException e) {
                throw new MetaGraphException("Meta Graph response for " + what
                        + " was not valid JSON", false, e);
            }
        }

        boolean retryable = status == 408 || status == 429 || status >= 500;
        String detail = extractError(response.body());
        // Deliberately not logging the URL — it carries the access token.
        log.warn("Meta Graph request for {} returned HTTP {} (retryable={}): {}",
                what, status, retryable, detail);
        throw new MetaGraphException("Meta Graph returned HTTP " + status
                + (detail == null ? "" : " — " + detail), retryable);
    }

    /** Best-effort extraction of {@code error.message} from a Graph error body. */
    private static String extractError(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            String message = error.path("message").asText(null);
            return (message == null || message.isBlank()) ? null : message;
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstValue(JsonNode values) {
        if (values == null || !values.isArray() || values.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (JsonNode v : values) {
            String text = v.asText(null);
            if (text == null || text.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(", ");
            }
            joined.append(text.trim());
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private String token() {
        return properties.pageAccessToken() == null ? "" : properties.pageAccessToken();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
