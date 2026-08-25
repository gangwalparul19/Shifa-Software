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

/* ── Portal-ready GSTR-1 (GST filing compliance, Req 5) ──────────────────────
 * Frontend models mirroring the backend GSTR-1 domain records + Gstr1ReturnResponse
 * one-to-one. BigDecimal figures serialise as numbers (typed `number | string` to
 * match the existing report models), LocalDate serialises as an ISO `yyyy-MM-dd`
 * string. Field names match the Java records exactly so the JSON binds directly.
 */

/** One invoice-level row of the GSTR-1 B2B section (registered buyer). */
export interface B2bRow {
  buyerGstin: string;
  orderCode: string;
  date: string;
  invoiceValue: number | string;
  placeOfSupply: string;
  stateCode: string;
  rate: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
}

/** One invoice-level row of the GSTR-1 B2CL (B2C large) section — IGST only. */
export interface B2clRow {
  orderCode: string;
  date: string;
  invoiceValue: number | string;
  placeOfSupply: string;
  stateCode: string;
  rate: number | string;
  taxable: number | string;
  igst: number | string;
}

/** One aggregated row of the GSTR-1 B2CS (B2C small) section. */
export interface B2csRow {
  type: string;
  placeOfSupply: string;
  stateCode: string;
  supplyType: SupplyType;
  rate: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
}

/** One row of the GSTR-1 CDNR section — credit/debit note against a B2B supply. */
export interface CdnrRow {
  buyerGstin: string;
  noteNumber: string;
  noteDate: string;
  originalOrderCode: string;
  placeOfSupply: string;
  stateCode: string;
  noteValue: number | string;
  rate: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
}

/** One row of the GSTR-1 CDNUR section — credit/debit note against a B2C supply. */
export interface CdnurRow {
  noteNumber: string;
  noteDate: string;
  originalOrderCode: string;
  placeOfSupply: string;
  stateCode: string;
  noteValue: number | string;
  rate: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
}

/** One row of the GSTR-1 Table-12 HSN summary (with UQC + compliance flag). */
export interface Gstr1HsnRow {
  hsn: string;
  uqc: string;
  rate: number | string;
  quantity: number | string;
  taxable: number | string;
  cgst: number | string;
  sgst: number | string;
  igst: number | string;
  compliant: boolean;
  complianceNote: string | null;
}

/** One row of the GSTR-1 Table-13 documents-issued summary. */
export interface DocRow {
  natureOfDocument: string;
  fromNumber: string;
  toNumber: string;
  totalCount: number;
  cancelledCount: number;
}

/** A place-of-supply state name that did not resolve to a 2-digit GST state code (Req 6.3). */
export interface UnresolvedStateFlag {
  stateName: string;
  context: string;
}

/**
 * The portal-ready GSTR-1 return for a reporting period (mirrors the backend
 * {@code Gstr1ReturnResponse}). The seven sections reconcile to {@link reconciliation}
 * (the same GSTR-3B totals the CA GST dashboard shows).
 */
export interface Gstr1Return {
  sellerGstin: string | null;
  month: number;
  year: number;
  /** The return period as {@code MMYYYY} (portal {@code fp}). */
  period: string;
  b2b: B2bRow[];
  b2cl: B2clRow[];
  b2cs: B2csRow[];
  cdnr: CdnrRow[];
  cdnur: CdnurRow[];
  hsn: Gstr1HsnRow[];
  docs: DocRow[];
  unresolvedStates: UnresolvedStateFlag[];
  reconciliation: Gstr3bSummary;
}

/** Alias matching the backend DTO name; identical shape to {@link Gstr1Return}. */
export type Gstr1ReturnResponse = Gstr1Return;
