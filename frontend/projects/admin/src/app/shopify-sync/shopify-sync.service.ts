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
}
