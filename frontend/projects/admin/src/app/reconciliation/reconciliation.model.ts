import { Money, PaymentStatus, ReceivableType } from 'core';

/**
 * A receivable row for the reconciliation table (backend
 * {@code ReceivableResponse}). Extends the core Receivable shape with the
 * resolved order code, courier name, and AWB (Req 18.1&ndash;18.3, 18.5).
 */
export interface ReceivableRow {
  id: number;
  orderId: number;
  orderCode: string | null;
  courierCompanyId: number | null;
  courierName: string | null;
  awb: string | null;
  type: ReceivableType;
  amount: Money;
  settled: boolean;
  settledDate?: string;
  createdAt?: string;
}

/**
 * Per-courier outstanding-receivable summary card
 * (backend {@code CourierSummaryResponse}, Req 18.1, 18.2, 18.6).
 */
export interface CourierSummary {
  courierCompanyId: number | null;
  courierName: string | null;
  codOutstanding: Money;
  claimOutstanding: Money;
  totalOutstanding: Money;
}

/**
 * A delivered COD order whose receivable is not yet settled
 * (backend {@code UnsettledCodResponse}, Req 18.3).
 */
export interface UnsettledCod {
  receivableId: number;
  orderId: number;
  orderCode: string | null;
  customerName: string | null;
  courierCompanyId: number | null;
  courierName: string | null;
  awb: string | null;
  amount: Money;
  createdAt?: string;
}

/** A single order within a prepaid/COD segregation group (Req 18.4). */
export interface SegregatedOrder {
  orderId: number;
  orderCode: string;
  customerName: string;
  paymentStatus: PaymentStatus;
  orderStatus: string;
  totalAmount: Money;
  codAmount: Money;
}

/** Prepaid vs COD segregation of fulfilled orders (backend {@code SegregationResponse}, Req 18.4). */
export interface Segregation {
  prepaid: SegregatedOrder[];
  cod: SegregatedOrder[];
  prepaidTotal: Money;
  codTotal: Money;
}
