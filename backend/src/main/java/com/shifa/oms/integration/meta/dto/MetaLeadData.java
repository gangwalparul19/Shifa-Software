package com.shifa.oms.integration.meta.dto;

import java.util.List;

/**
 * The full lead field data fetched from the Meta Graph API for a {@code leadgen_id}
 * (spec {@code meta-lead-sync}, Req 5.2).
 *
 * @param leadgenId the lead id fetched
 * @param formName  the Instant Form name, used as the lead source note (may be null)
 * @param fields    the submitted answers, in form order
 */
public record MetaLeadData(String leadgenId, String formName, List<MetaField> fields) {
}
