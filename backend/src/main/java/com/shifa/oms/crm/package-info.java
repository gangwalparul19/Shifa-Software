/**
 * Customer CRM ("operations depth" Feature 1): a read-only, aggregate view of
 * customers built entirely over the existing {@code orders} table (no new table).
 *
 * <p>A customer is identified by {@code customer_mobile}.
 * {@link com.shifa.oms.crm.CustomerService} aggregates orders per mobile into
 * summaries (order count, lifetime value, first/last order, repeat-buyer flag)
 * and enriches each with a "registered" flag by matching against
 * {@link com.shifa.oms.auth.Role#CUSTOMER} accounts.
 * {@link com.shifa.oms.crm.CustomerController} exposes the paged list and a
 * per-customer detail (summary + order history) at {@code /api/admin/customers}
 * (ADMIN + ACCOUNTANT).
 */
package com.shifa.oms.crm;
