/**
 * The unified CRM activity log (CRM expansion Phase 2, Task 9, design &sect;2.1).
 *
 * <p>{@link com.shifa.oms.activity.ActivityService} is the single writer; every
 * CRM touch point (lead stage/tag/assign/follow-up, tasks, meetings, and the
 * frontend call/WhatsApp log) records an append-only {@link
 * com.shifa.oms.activity.ActivityEntry}. The read side powers the per-lead and
 * per-customer timelines (Req 11); the per-customer timeline merges these rows
 * with the customer's order history (Customer 360).
 */
package com.shifa.oms.activity;
