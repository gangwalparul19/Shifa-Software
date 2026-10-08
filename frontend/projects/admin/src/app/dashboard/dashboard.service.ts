import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  ActivityCards,
  ChannelMarginReport,
  DashboardMetrics,
  LiveStats,
  MetricsPeriod,
  OwnerSnapshot,
  RoleDashboardSummary,
  SalesBucket,
} from './dashboard.model';
import { ChannelDashboardData, DashboardChannel } from './channel-dashboard.model';
import { DeliveryPartnerSummary } from './delivery-partner.model';

/**
 * Data access for the admin dashboard metrics API (Req 19.1-19.7).
 *
 * <p>All calls go through the shared {@link ApiClient} so the global auth
 * interceptor attaches the bearer token; the backend enforces the ADMIN role.
 */
@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly api = inject(ApiClient);

  /**
   * Metric cards, sales graph, and top performers for a period (Req 19.1-19.4, 19.7).
   * For {@code CUSTOM}, pass {@code from}/{@code to} as ISO dates.
   */
  metrics(
    period: MetricsPeriod,
    bucket: SalesBucket | null,
    from: string | null,
    to: string | null,
  ): Observable<DashboardMetrics> {
    let params = new HttpParams().set('period', period);
    if (bucket) {
      params = params.set('bucket', bucket);
    }
    if (period === 'CUSTOM') {
      if (from) {
        params = params.set('from', from);
      }
      if (to) {
        params = params.set('to', to);
      }
    }
    return this.api.get<DashboardMetrics>('/api/admin/metrics', { params });
  }

  /**
   * The channel-aware admin dashboard (Portal / Shopify / All) for a period.
   * For {@code CUSTOM}, pass {@code from}/{@code to} as ISO dates.
   */
  channelDashboard(
    channel: DashboardChannel,
    period: MetricsPeriod,
    bucket: SalesBucket | null,
    from: string | null,
    to: string | null,
  ): Observable<ChannelDashboardData> {
    let params = new HttpParams().set('channel', channel).set('period', period);
    if (bucket) {
      params = params.set('bucket', bucket);
    }
    if (period === 'CUSTOM') {
      if (from) {
        params = params.set('from', from);
      }
      if (to) {
        params = params.set('to', to);
      }
    }
    return this.api.get<ChannelDashboardData>('/api/admin/dashboard/channel', { params });
  }

  /**
   * Orders split by delivery partner (QuikShipX / in-house / POS) over an
   * optional inclusive date window ({@code GET /api/admin/orders/delivery-partner-summary}).
   * ADMIN only. Null from/to = all-time.
   */
  deliveryPartnerSummary(from?: string | null, to?: string | null): Observable<DeliveryPartnerSummary> {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.api.get<DeliveryPartnerSummary>('/api/admin/orders/delivery-partner-summary', { params });
  }

  /** Real-time live statistics (Req 19.5). */
  liveStats(): Observable<LiveStats> {
    return this.api.get<LiveStats>('/api/admin/metrics/live');
  }

  /** Activity-card counts (Req 19.6). */
  activity(): Observable<ActivityCards> {
    return this.api.get<ActivityCards>('/api/admin/metrics/activity');
  }

  /**
   * The role-shaped dashboard summary for the current user (design §6.7, §7.1,
   * Req 3.1–3.6). Available to all four operational roles; the backend shapes
   * the payload to the caller's role and scopes a salesperson to their own
   * orders.
   */
  roleSummary(): Observable<RoleDashboardSummary> {
    return this.api.get<RoleDashboardSummary>('/api/dashboard/summary');
  }

  /**
   * The owner's one-screen snapshot — today's trading + the actionable backlog
   * (ENHANCEMENT 1.2). ADMIN only.
   */
  ownerSnapshot(): Observable<OwnerSnapshot> {
    return this.api.get<OwnerSnapshot>('/api/admin/dashboard/owner-snapshot');
  }

  /**
   * Per-channel revenue + estimated gross margin (ENHANCEMENT 3.6). Optional
   * inclusive from/to window. ADMIN only.
   */
  channelMargin(from?: string, to?: string): Observable<ChannelMarginReport> {
    let params = new HttpParams();
    if (from) {
      params = params.set('from', from);
    }
    if (to) {
      params = params.set('to', to);
    }
    return this.api.get<ChannelMarginReport>('/api/admin/dashboard/channel-margin', { params });
  }
}
