package com.shifa.oms.performance;

/**
 * The horizon a sales target covers (CRM expansion Phase 2, Task 13; Req 12.1):
 * a calendar {@code MONTH} (the shipped behaviour) or an ISO {@code WEEK}
 * anchored on its Monday.
 */
public enum TargetPeriodType {
    WEEK, MONTH
}
