/**
 * Lead management &amp; sales pipeline (Lead Management feature).
 *
 * <p>Adds a pre-order {@link com.shifa.oms.lead.LeadEntity Lead} aggregate and a
 * small sales pipeline that mirrors the {@code order} module's conventions: a
 * lead is captured the moment an enquiry arrives, worked through
 * {@code NEW → CONTACTED → QUOTED} via the frozen
 * {@link com.shifa.oms.lead.LeadStatus} transition table, and either converted
 * into an order ({@code WON}) or marked {@code LOST} with a categorized
 * {@link com.shifa.oms.lead.LostReason}. {@link com.shifa.oms.lead.LeadService}
 * is the single entry point, owning validation, legality, salesperson scoping,
 * status history, and audit. The channel reuses
 * {@link com.shifa.oms.order.LeadSource}.
 */
package com.shifa.oms.lead;
