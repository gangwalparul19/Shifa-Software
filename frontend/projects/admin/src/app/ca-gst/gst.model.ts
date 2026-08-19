/** Frontend models mirroring the backend CA GST DTOs (CA GST dashboard). */

export interface GstSeller {
  legalName: string;
  gstin?: string | null;
  state?: string | null;
  stateCode?: string | null;
}

export interface RateWiseRow {
  rate: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
  invoiceValue: number | string;
}

export interface HsnRow {
  hsn: string;
  description: string;
  quantity: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
}

export type SupplyType = 'INTRA' | 'INTER';

export interface StateWiseRow {
  state: string;
  type: SupplyType;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
}

export interface Gstr3bSummary {
  taxableOutward: number | string;
  outputCgst: number | string;
  outputSgst: number | string;
  outputIgst: number | string;
  outputTotal: number | string;
  invoiceValue: number | string;
}

export interface GstReport {
  seller: GstSeller;
  from: string;
  to: string;
  sellerStateConfigured: boolean;
  rateWise: RateWiseRow[];
  hsn: HsnRow[];
  stateWise: StateWiseRow[];
  summary: Gstr3bSummary;
}

export interface CategoryAmount {
  category: string;
  amount: number | string;
}

export interface MoneyFlows {
  grossSales: number | string;
  taxableSales: number | string;
  outputGst: number | string;
  amountReceived: number | string;
  codCollected: number | string;
  purchases: number | string;
  expenses: number | string;
  expensesByCategory: CategoryAmount[];
  refunds: number | string;
  outstandingCod: number | string;
  netCash: number | string;
}

export interface GstDashboard {
  report: GstReport;
  money: MoneyFlows;
}

/** One order behind a GST figure (drill-down), with its remaining dues. */
export interface GstOrderRow {
  orderId: number;
  orderCode: string;
  orderDate: string | null;
  customerName: string;
  customerMobile: string;
  state: string | null;
  supplyType: SupplyType;
  taxable: number | string;
  tax: number | string;
  total: number | string;
  received: number | string;
  remaining: number | string;
  /** Non-COD balance the customer still owes directly. */
  customerRemaining: number | string;
  /** COD still to be collected/remitted by the courier. */
  codPending: number | string;
  paymentStatus: string | null;
  orderStatus: string | null;
}

/** A drill-down filter for the orders behind a summary row. */
export interface GstOrderFilter {
  state?: string | null;
  rate?: number | string | null;
  hsn?: string | null;
}
