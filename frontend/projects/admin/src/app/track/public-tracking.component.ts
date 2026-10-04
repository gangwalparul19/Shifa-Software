import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ApiClient } from 'core';

/** One step on the customer tracking timeline. */
interface TrackStep {
  label: string;
  reached: boolean;
}

/** The public tracking projection (backend {@code PublicTrackingResponse}). */
interface PublicTracking {
  orderCode: string;
  customerName: string | null;
  statusLabel: string;
  stage: string | null;
  awb: string | null;
  courierName: string | null;
  trackingUrl: string | null;
  estimatedDelivery: string | null;
  delivered: boolean;
  timeline: TrackStep[];
}

/**
 * Public, unauthenticated customer order-tracking page (ENHANCEMENT 2.2),
 * reached at {@code /track/:token} outside the admin shell/guards. Resolves the
 * order by its opaque token via {@code GET /api/track/t/{token}} and shows a
 * friendly status, a stage timeline, and the courier tracking link — no login,
 * no internal data. Self-contained styling so it needs no admin chrome.
 */
@Component({
  selector: 'shifa-public-tracking',
  standalone: true,
  template: `
    <div class="pt-wrap">
      <div class="pt-card">
        <div class="pt-brand">
          <img src="/logo.png" alt="Shifa Herbal Remedies" class="pt-logo" />
          <div class="pt-brandname">Shifa Herbal Remedies</div>
        </div>

        @if (loading()) {
          <div class="pt-muted pt-center">Loading your order status…</div>
        } @else if (error()) {
          <div class="pt-center">
            <div class="pt-status pt-bad">We couldn't find that order</div>
            <p class="pt-muted">This tracking link may be incorrect or expired. Please check the link in your message.</p>
          </div>
        } @else if (data(); as d) {
          <div class="pt-head">
            @if (d.customerName) { <div class="pt-hi">Hi {{ d.customerName }},</div> }
            <div class="pt-order">Order <strong>{{ d.orderCode }}</strong></div>
          </div>

          <div class="pt-status" [class.pt-good]="d.delivered">{{ d.statusLabel }}</div>

          <ol class="pt-timeline">
            @for (s of d.timeline; track s.label) {
              <li [class.pt-reached]="s.reached">
                <span class="pt-dot"></span>
                <span class="pt-steplabel">{{ s.label }}</span>
              </li>
            }
          </ol>

          @if (d.awb || d.estimatedDelivery) {
            <div class="pt-ship">
              @if (d.courierName) { <div><span class="pt-muted">Courier</span><span>{{ d.courierName }}</span></div> }
              @if (d.awb) { <div><span class="pt-muted">Tracking #</span><span class="pt-mono">{{ d.awb }}</span></div> }
              @if (d.estimatedDelivery) { <div><span class="pt-muted">Expected by</span><span>{{ d.estimatedDelivery }}</span></div> }
            </div>
          }

          @if (d.trackingUrl) {
            <a class="pt-btn" [href]="d.trackingUrl" target="_blank" rel="noopener">Track with courier</a>
          }
        }

        <div class="pt-foot pt-muted">Thank you for choosing Shifa Herbal Remedies.</div>
      </div>
    </div>
  `,
  styles: [
    `
      .pt-wrap {
        min-height: 100vh;
        background: #f6f8f6;
        display: flex;
        align-items: flex-start;
        justify-content: center;
        padding: 24px 16px;
        font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
        color: #1a1a1a;
      }
      .pt-card {
        width: 100%;
        max-width: 460px;
        background: #fff;
        border-radius: 16px;
        box-shadow: 0 6px 24px rgba(0, 0, 0, 0.08);
        padding: 24px;
      }
      .pt-brand { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; }
      .pt-logo { width: 40px; height: 40px; object-fit: contain; }
      .pt-brandname { font-weight: 700; color: #1f5d3f; }
      .pt-center { text-align: center; padding: 20px 0; }
      .pt-muted { color: #6b7280; }
      .pt-head { margin-bottom: 10px; }
      .pt-hi { color: #6b7280; }
      .pt-order { font-size: 1.05rem; }
      .pt-status {
        font-size: 1.25rem;
        font-weight: 700;
        color: #1f5d3f;
        margin: 10px 0 18px;
      }
      .pt-status.pt-good { color: #2fb344; }
      .pt-status.pt-bad { color: #d63939; }
      .pt-timeline { list-style: none; margin: 0 0 18px; padding: 0; }
      .pt-timeline li {
        display: flex;
        align-items: center;
        gap: 10px;
        padding: 6px 0;
        color: #9aa0a6;
      }
      .pt-timeline li.pt-reached { color: #1a1a1a; }
      .pt-dot {
        width: 12px; height: 12px; border-radius: 50%;
        background: #d7dbd9; flex: none;
      }
      .pt-timeline li.pt-reached .pt-dot { background: #1f5d3f; }
      .pt-steplabel { font-weight: 500; }
      .pt-ship {
        border-top: 1px solid #eef0ee;
        padding-top: 12px;
        margin-bottom: 16px;
        display: grid;
        gap: 6px;
      }
      .pt-ship > div { display: flex; justify-content: space-between; }
      .pt-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
      .pt-btn {
        display: block;
        text-align: center;
        background: #1f5d3f;
        color: #fff;
        text-decoration: none;
        padding: 12px;
        border-radius: 10px;
        font-weight: 600;
      }
      .pt-foot { text-align: center; margin-top: 18px; font-size: 0.85rem; }
    `,
  ],
})
export class PublicTrackingComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiClient);

  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly data = signal<PublicTracking | null>(null);

  ngOnInit(): void {
    const token = this.route.snapshot.paramMap.get('token');
    if (!token) {
      this.loading.set(false);
      this.error.set(true);
      return;
    }
    // Public endpoint — the auth interceptor only attaches a token when one
    // exists, and a customer on this page has no session, so no token is sent.
    this.api.get<PublicTracking>(`/api/track/t/${encodeURIComponent(token)}`).subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }
}
