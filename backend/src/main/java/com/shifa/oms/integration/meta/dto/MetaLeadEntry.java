package com.shifa.oms.integration.meta.dto;

/**
 * One {@code leadgen} change entry parsed from a Meta webhook notification (spec
 * {@code meta-lead-sync}, Req 3.1). The {@code leadgenId} is the idempotency key
 * and the handle used to fetch the full field data from the Graph API.
 *
 * @param leadgenId   Meta's lead id for this submission
 * @param formId      the Instant Form id that produced the lead (may be null)
 * @param pageId      the business Page id that owns the ad/form (may be null)
 * @param createdTime the submission unix time in seconds (may be null)
 */
public record MetaLeadEntry(String leadgenId, String formId, String pageId, Long createdTime) {
}
