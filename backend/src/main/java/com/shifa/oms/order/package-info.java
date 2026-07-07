/**
 * Order / OMS module.
 *
 * <p>Owns the Order aggregate: line items, payment math, derived amounts,
 * lifecycle status, status history, and receivables. Delegates transitions to
 * the {@code statemachine} package.
 */
package com.shifa.oms.order;
