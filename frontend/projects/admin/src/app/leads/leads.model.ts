import { Money } from 'core';
import { CreateOrderLineItem, LeadSource } from '../orders/orders.model';

/**
 * Client-side models for the Lead Management / sales-pipeline feature, mirroring
 * the backend {@code com.shifa.oms.lead} DTOs (design §Frontend, §API). The
 * origin channel reuses the shared {@link LeadSource} enum from the orders
 * feature (the backend reuses {@code order.LeadSource} too).
 */

/** The lead pipeline status (backend {@code LeadStatus}). */
export type LeadStatus = 'NEW' | 'CONTACTED' | 'QUOTED' | 'WON' | 'LOST';

/** The categorized reason a lead did not convert (backend {@code LostReason}). */
export type LostReason =
  | 'PRICE'
  | 'OUT_OF_STOCK'
  | 'NO_RESPONSE'
  | 'DUPLICATE'
  | 'NOT_INTERESTED'
  | 'OTHER';

/** Selectable lost-reason options for the "Mark lost" picker (Req 2.3). */
export const LOST_REASON_OPTIONS: { value: LostReason; label: string }[] = [
  { value: 'PRICE', label: 'Price' },
  { value: 'OUT_OF_STOCK', label: 'Out of stock' },
  { value: 'NO_RESPONSE', label: 'No response' },
  { value: 'DUPLICATE', label: 'Duplicate' },
  { value: 'NOT_INTERESTED', label: 'Not interested' },
  { value: 'OTHER', label: 'Other' },
];

/**
 * The ordered pipeline stages shown on the board and used to render per-stage
 * count columns / grouped cards. Terminal states (WON/LOST) are included so the
 * pipeline reads end-to-end.
 */
export const LEAD_STAGE_ORDER: LeadStatus[] = ['NEW', 'CONTACTED', 'QUOTED', 'WON', 'LOST'];

/** Human labels for each {@link LeadStatus}. */
export const LEAD_STATUS_LABELS: Record<LeadStatus, string> = {
  NEW: 'New',
  CONTACTED: 'Contacted',
  QUOTED: 'Quoted',
  WON: 'Won',
  LOST: 'Lost',
};

/**
 * Lead-capture payload posted to {@code POST /api/leads} (mirrors the backend
 * {@code CreateLeadRequest}). Also used verbatim by {@code PUT /api/leads/{id}}
 * to edit capture fields while the lead is non-terminal (Req 1, 3.6).
 */
export interface CreateLeadRequest {
  customerName: string;
  leadSource: LeadSource;
  leadSourceNote?: string;
  customerMobile?: string;
  customerEmail?: string;
  note?: string;
  followUpDate?: string | null;
}

/** One status-history row on the lead detail trail (backend {@code StatusHistoryEntry}). */
export interface LeadStatusHistoryEntry {
  fromStatus: LeadStatus | null;
  toStatus: LeadStatus;
  actor: string;
  changedAt: string;
}

/**
 * Full lead detail returned by {@code POST /api/leads} and
 * {@code GET /api/leads/{id}} (mirrors the backend {@code LeadResponse}).
 */
export interface LeadDetail {
  id: number;
  customerName: string;
  customerMobile?: string | null;
  customerEmail?: string | null;
  leadSource: LeadSource;
  leadSourceNote?: string | null;
  status: LeadStatus;
  lostReason?: LostReason | null;
  lostReasonNote?: string | null;
  note?: string | null;
  followUpDate?: string | null;
  ownerUserId: number;
  convertedOrderId?: number | null;
  createdAt?: string;
  updatedAt?: string;
  statusHistory: LeadStatusHistoryEntry[];
}

/**
 * Compact lead projection from the list / pipeline / due-follow-ups endpoints
 * (mirrors the backend {@code LeadSummaryResponse}).
 */
export interface LeadSummary {
  id: number;
  customerName: string;
  customerMobile?: string | null;
  leadSource: LeadSource;
  status: LeadStatus;
  followUpDate?: string | null;
  ownerUserId: number;
  convertedOrderId?: number | null;
  createdAt?: string;
}

/** Advance-status payload for {@code POST /api/leads/{id}/status} (backend {@code LeadStatusChangeRequest}). */
export interface LeadStatusChangeRequest {
  toStatus: LeadStatus;
  lostReason?: LostReason;
  lostReasonNote?: string;
}

/** Set/clear follow-up payload for {@code PUT /api/leads/{id}/follow-up}. */
export interface FollowUpRequest {
  followUpDate: string | null;
}

/**
 * Convert-to-order payload for {@code POST /api/leads/{id}/convert} (mirrors the
 * backend {@code LeadConvertRequest}). The customer identity and lead source are
 * NOT sent — the server forces them from the lead being converted (Req 4.2, 4.3).
 */
export interface LeadConvertRequest {
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
  items: CreateOrderLineItem[];
  amountReceived: number;
  paymentScreenshotKey?: string;
  /** Optional free-text order note captured at conversion (≤1000 chars). */
  notes?: string;
  /** Optional order-level discount carried to the created order (Flat/Percent). */
  discountType?: 'FLAT' | 'PERCENT';
  /** The raw discount value entered (rupee amount for FLAT, percent for PERCENT). */
  discountValue?: number;
}

/** Filters for the scoped lead list (`GET /api/leads`). */
export interface LeadListQuery {
  q?: string | null;
  status?: LeadStatus | null;
  source?: LeadSource | null;
}

// --- Reports (Req 6) -------------------------------------------------------

/** One row of the leads-by-source report. */
export interface SourceCount {
  source: LeadSource;
  count: number;
}

/** The leads-by-source report. */
export interface BySourceReport {
  rows: SourceCount[];
  total: number;
}

/** One conversion row (source name or owner id → leads/won/rate). */
export interface ConversionRow {
  key: string;
  leads: number;
  won: number;
  conversionRate: string;
}

/** The conversion report grouped by source and by owner. */
export interface ConversionReport {
  bySource: ConversionRow[];
  byOwner: ConversionRow[];
}

/** One pipeline-snapshot row. */
export interface PipelineCount {
  status: LeadStatus;
  count: number;
}

/** The pipeline-snapshot report. */
export interface PipelineReport {
  rows: PipelineCount[];
  total: number;
}

/** One lost-reasons row. */
export interface LostReasonCount {
  reason: LostReason;
  count: number;
}

/** The lost-reasons report. */
export interface LostReasonReport {
  rows: LostReasonCount[];
  total: number;
}

/** Convenience: the coarse colour group for a lead-status pill. */
export function leadPillClass(status: LeadStatus): 'is-green' | 'is-amber' | 'is-red' | 'is-blue' {
  switch (status) {
    case 'WON':
      return 'is-green';
    case 'LOST':
      return 'is-red';
    case 'NEW':
      return 'is-blue';
    default:
      return 'is-amber';
  }
}

/** Money formatting helper (Money is a decimal string). */
export function leadMoney(value: Money | number | undefined | null): string {
  if (value === undefined || value === null) {
    return '₹0.00';
  }
  return `₹${value}`;
}
