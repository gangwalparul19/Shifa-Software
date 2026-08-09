package com.shifa.oms.integration.meta.dto;

/**
 * One submitted answer from a Meta Lead Ads Instant Form (spec
 * {@code meta-lead-sync}, Req 5.2, 6). Maps a Graph API {@code field_data} entry:
 * {@code {"name": "phone_number", "values": ["+9198..."]}} → {@code name} +
 * the first (joined) value.
 *
 * @param name  the form field key (e.g. {@code full_name}, {@code phone_number}, {@code email})
 * @param value the submitted value (first value; multiple values are joined)
 */
public record MetaField(String name, String value) {
}
