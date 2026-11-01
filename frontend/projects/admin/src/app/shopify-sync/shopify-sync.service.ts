import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** A Shopify order shown on the sync page, with its real QuikShipX shipment state. */
export interface StuckShopifyOrder {
  id: number;
  orderCode: string;
  customerName: string;
  customerMobile: string;
  /** PENDING_ADMIN_APPROVAL or LABEL_GENERATED (OMS lifecycle status). */
  status: string;
  totalAmount: number;
  createdAt: string;
  /** Delivered by our own team — never sent to QuikShipX. */
  inHouse: boolean;
  /** Whether a recover run will act on this order. */
  recoverable: boolean;
  /** The QuikShipX tracking id (AWB), present once allotted. */
  awb: string | null;
  /** The QuikShipX shipment status label (e.g. "Tracking ID Assigned", "Confirmed"). */
  quikShipXStatus: string | null;
  /** True once the shipment has an AWB — the order is in sync, not stuck. */
  trackingAssigned: boolean;
  /** True when the order still needs a tracking id (recoverable + no AWB yet). */
  waiting: boolean;
  /**
   * Why QuikShipX could not ship it (e.g. "585216 is non serviceable pincode"),
   * when a permanent failure was recorded. Null when it is simply still being
   * processed. Drives the "re-route to in-house" prompt.
   */
  failureReason: string | null;
}

/** The Shopify integration switch. */
export interface ShopifyIntegrationSettings {
  enabled: boolean;
}

/** Outcome of one recover run. */
export interface RecoverResult {
  approvedFromPending: number;
  republishedFromLabelGenerated: number;
}

/**
 * Where prepaid Shopify amounts are booked. Shopify collects the money itself and pays it out
 * later, so every Shopify payment belongs under the Shopify settlement account.
 */
export interface ShopifySettlementStatus {
  /** False when no active account with the "Shopify settlement" purpose exists. */
  configured: boolean;
  accountId: number | null;
  accountName: string | null;
  accountMasked: string | null;
  /** Existing Shopify payments that still have no account (imported before one was set up). */
  unassignedPayments: number;
  unassignedAmount: number;
  assignedPayments: number;
}

export interface AssignSettlementResult {
  assigned: number;
  accountName: string;
}

/**
 * Data access for the ADMIN-only Shopify sync page
 * ({@code /api/admin/shopify/stuck} + {@code /api/admin/shopify/recover}).
 */
@Injectable({ providedIn: 'root' })
export class ShopifySyncService {
  private readonly api = inject(ApiClient);

  /** Shopify orders still waiting for a QuikShipX tracking id. */
  stuck(): Observable<StuckShopifyOrder[]> {
    return this.api.get<StuckShopifyOrder[]>('/api/admin/shopify/stuck');
  }

  /** Whether incoming Shopify orders are imported (the integration switch). */
  integration(): Observable<ShopifyIntegrationSettings> {
    return this.api.get<ShopifyIntegrationSettings>('/api/admin/shopify/settings');
  }

  /** Turns the Shopify integration on or off. */
  setIntegration(enabled: boolean): Observable<ShopifyIntegrationSettings> {
    return this.api.put<ShopifyIntegrationSettings>('/api/admin/shopify/settings', { enabled });
  }

  /** Pushes every stuck Shopify order forward (idempotent, safe to repeat). */
  recover(): Observable<RecoverResult> {
    return this.api.post<RecoverResult>('/api/admin/shopify/recover', {});
  }

  /** The account Shopify payments are booked under, plus how many still need assigning. */
  settlementAccount(): Observable<ShopifySettlementStatus> {
    return this.api.get<ShopifySettlementStatus>('/api/admin/shopify/settlement-account');
  }

  /** Books every Shopify payment that has no account under the settlement account (idempotent). */
  assignSettlementAccount(): Observable<AssignSettlementResult> {
    return this.api.post<AssignSettlementResult>('/api/admin/shopify/settlement-account/assign', {});
  }
}
