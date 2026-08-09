package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.meta.dto.MetaLeadData;

/**
 * Outbound Meta Graph API access (spec {@code meta-lead-sync}, Req 5, 10).
 *
 * <p>An interface so the ingest service and connectivity test can be exercised with
 * a recording/stub implementation in tests (Java 25 cannot Mockito-mock concrete
 * classes), while {@link HttpMetaGraphClient} makes the real calls in production.
 */
public interface MetaGraphClient {

    /**
     * Fetches the full field data for a lead.
     *
     * @param leadgenId the Meta lead id from the webhook notification
     * @return the submitted fields + form name
     * @throws MetaGraphException retryable on transient faults (timeout/5xx/429/IO),
     *                            non-retryable on an authorization failure (Req 5.3, 5.4)
     */
    MetaLeadData fetchLead(String leadgenId);

    /**
     * Fetches the configured Page's name, for the admin connectivity test (Req 10.1).
     *
     * @return the Page name reported by Meta
     * @throws MetaGraphException on any failure; the message carries the Graph error
     */
    String fetchPageName();
}
