package com.shifa.oms.integration.meta;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shifa.oms.integration.MalformedPayloadException;
import com.shifa.oms.integration.meta.dto.MetaLeadEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure parser for a Meta Lead Ads webhook notification body (spec
 * {@code meta-lead-sync}, Req 3.1, 3.4).
 *
 * <p>The notification shape is:
 * <pre>
 * {
 *   "object": "page",
 *   "entry": [{
 *     "id": "&lt;page_id&gt;",
 *     "time": 1700000000,
 *     "changes": [{
 *       "field": "leadgen",
 *       "value": {
 *         "leadgen_id": "...", "form_id": "...", "page_id": "...", "created_time": 1700000000
 *       }
 *     }]
 *   }]
 * }
 * </pre>
 *
 * <p>Only {@code changes[].field == "leadgen"} entries are kept; anything else is
 * ignored (Req 3.4). No Spring, no I/O — a total function over the raw text.
 */
public final class MetaLeadNotificationCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LEADGEN_FIELD = "leadgen";

    private MetaLeadNotificationCodec() {
        // Pure static helper.
    }

    /**
     * @param json the raw notification body as UTF-8 text
     * @return the {@code leadgen} entries, in document order (possibly empty)
     * @throws MalformedPayloadException when the body is not parseable JSON
     */
    public static List<MetaLeadEntry> parse(String json) {
        if (json == null || json.isBlank()) {
            throw new MalformedPayloadException("body", "the Meta notification body was empty");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new MalformedPayloadException("body", "the Meta notification body was not valid JSON");
        }

        List<MetaLeadEntry> entries = new ArrayList<>();
        JsonNode entryArray = root.path("entry");
        if (!entryArray.isArray()) {
            return entries;
        }
        for (JsonNode entry : entryArray) {
            JsonNode changes = entry.path("changes");
            if (!changes.isArray()) {
                continue;
            }
            for (JsonNode change : changes) {
                if (!LEADGEN_FIELD.equals(change.path("field").asText(null))) {
                    continue;
                }
                JsonNode value = change.path("value");
                String leadgenId = text(value, "leadgen_id");
                if (leadgenId == null) {
                    // A leadgen change without a lead id is unusable; skip it rather than
                    // storing an event we can never fetch.
                    continue;
                }
                entries.add(new MetaLeadEntry(
                        leadgenId,
                        text(value, "form_id"),
                        text(value, "page_id"),
                        value.hasNonNull("created_time") ? value.get("created_time").asLong() : null));
            }
        }
        return entries;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return (text == null || text.isBlank()) ? null : text.trim();
    }
}
