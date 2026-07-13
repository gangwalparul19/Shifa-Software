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

// --- Customer 360 / CRM depth (FEATURE-ROADMAP §1) ------------------------

/** A customer's delivery-reliability risk band. */
export type CustomerRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

/** Derived delivery-reliability + money metrics for the 360 profile. */
export interface CustomerMetrics {
  deliveredCount: number;
  failedDeliveryCount: number;
  inFlightCount: number;
  cancelledCount: number;
  /** delivered / (delivered + failed), 0..1. */
  successRate: number;
  outstanding: Money;
}

/**
 * A customer's delivery-reliability risk assessment
 * ({@code GET /api/admin/customers/{mobile}/risk}), also embedded in the profile.
 */
export interface CustomerRisk {
  mobile: string;
  level: CustomerRiskLevel;
  failedDeliveryCount: number;
  deliveredCount: number;
  failureRate: number;
  priorOrderCount: number;
  repeatBuyer: boolean;
  message: string;
}

/** A product the customer has bought, aggregated across their orders. */
export interface TopProduct {
  productId?: number | null;
  productName: string;
  quantity: number;
  amount: Money;
}

/** Count of the customer's orders in a given lifecycle status. */
export interface CustomerStatusCount {
  status: OrderStatus | string;
  count: number;
}

/** A single staff note on the customer's timeline. */
export interface CustomerNote {
  id: number;
  note: string;
  authorName?: string | null;
  createdAt: string;
}

/**
 * The full "Customer 360" profile returned by
 * {@code GET /api/admin/customers/{mobile}/profile}.
 */
export interface CustomerProfile {
  summary: CustomerSummary;
  metrics: CustomerMetrics;
  risk: CustomerRisk;
  topProducts: TopProduct[];
  statusBreakdown: CustomerStatusCount[];
  tags: string[];
  notes: CustomerNote[];
  orders: CustomerOrder[];
}

/** Tabler badge class for a risk band (green/amber/red). */
export function riskPillClass(level: CustomerRiskLevel | string): string {
  switch (level) {
    case 'HIGH':
      return 'bg-red-lt';
    case 'MEDIUM':
      return 'bg-yellow-lt';
    default:
      return 'bg-green-lt';
  }
}

/** Human label for a risk band. */
export function riskLabel(level: CustomerRiskLevel | string): string {
  switch (level) {
    case 'HIGH':
      return 'High risk';
    case 'MEDIUM':
      return 'Medium risk';
    default:
      return 'Low risk';
  }
}
