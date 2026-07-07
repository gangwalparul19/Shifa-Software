import { Money, OrderStatus, PaymentStatus } from 'core';

/**
 * A single customer row / summary returned by the admin CRM endpoints
 * ({@code GET /api/admin/customers}). A customer is keyed by their mobile
 * number; {@code registered} marks whether a storefront account exists, and
 * {@code repeatBuyer} flags customers with more than one order.
 */
export interface CustomerSummary {
  mobile: string;
  name: string;
  registered: boolean;
  orderCount: number;
  totalSpent: Money;
  lastOrderAt?: string | null;
  firstOrderAt?: string | null;
  repeatBuyer: boolean;
}

/** A compact order row shown in a customer's order-history list. */
export interface CustomerOrder {
  orderCode: string;
  date?: string | null;
  total: Money;
  orderStatus: OrderStatus | string;
  paymentStatus: PaymentStatus | string;
}

/**
 * Full customer detail returned by {@code GET /api/admin/customers/{mobile}}:
 * the summary plus the customer's order history.
 */
export interface CustomerDetail {
  summary: CustomerSummary;
  orders: CustomerOrder[];
}
