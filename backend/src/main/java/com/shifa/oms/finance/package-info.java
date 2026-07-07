/**
 * Finance module (Feature C3): business expenses and the profit &amp; loss
 * report.
 *
 * <p>Expenses are manually recorded costs (rent, salaries, marketing, ...). The
 * P&amp;L report combines order revenue, courier/claim costs derived from the
 * reconciliation receivables, and expenses into a net-profit summary for a date
 * window (computed on the fly, stored nowhere). Because the schema does not
 * model per-shipment courier charges, {@code courierCost} is derived as the
 * value of loss/damage claims not yet recovered from the courier; see
 * {@link com.shifa.oms.finance.dto.ProfitLossResponse} for the full definitions.
 *
 * <p>Endpoints live under {@code /api/admin/*} and are open to ADMIN and
 * ACCOUNTANT.
 */
package com.shifa.oms.finance;
