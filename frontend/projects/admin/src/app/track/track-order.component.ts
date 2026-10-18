import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiClient } from 'core';

/** One step on the customer tracking timeline (backend {@code PublicTrackingResponse.Step}). */
interface TrackStep {
  label: string;
  reached: boolean;
}

/**
 * One detailed courier scan event (backend {@code PublicTrackingResponse.Event})
 * — the persisted QuikShipX scan timeline (status + movement instruction +
 * facility/location + scan time).
 */
interface TrackEvent {
  status: string | null;
  instructions: string | null;
  location: string | null;
  scanAt: string | null;
}

/** The per-order public tracking projection ({@code GET /api/track/t/{token}}). */
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
  events: TrackEvent[];
}

/** One order card from the by-mobile lookup ({@code TrackByMobileResponse.OrderCard}). */
interface OrderCard {
  orderCode: string;
  trackingToken: string;
  productSummary: string;
  statusLabel: string;
  stage: string | null;
  delivered: boolean;
  placedOn: string | null;
}

interface TrackByMobile {
  brandName: string | null;
  orders: OrderCard[];
}

/**
 * Public, unauthenticated self-service order-tracking page, reached at
 * {@code /track-order} outside the admin shell/guards. A customer enters the
 * mobile number they registered with, picks one of their orders, and sees its
 * live tracking — no login — saving a WhatsApp notification. Three states in one
 * self-contained component: enter mobile → pick an order → tracking detail.
 */
@Component({
  selector: 'shifa-track-order',
  standalone: true,
  imports: [FormsModule, NgTemplateOutlet],
  template: `
    <div class="to-wrap">
      <div class="to-card" [class.to-card--wide]="view() === 'detail'">
        <!-- Brand header (gradient) -->
        <div class="to-brand">
          <div class="to-brand__logo"><img src="/logo.png" [alt]="brandName()" /></div>
          <div class="to-brand__name">
            <div class="to-brand__title">{{ brandName() }}</div>
            <div class="to-brand__sub">Order tracking</div>
          </div>
        </div>

        <div class="to-body">
          <!-- ================= STATE 1: enter mobile ================= -->
          @if (view() === 'search') {
            <div class="to-hero">
              <div class="to-hero__icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M1 6h13v9H1z"/><path d="M14 9h4l3 3v3h-7z"/><circle cx="6" cy="18" r="1.8"/><circle cx="17" cy="18" r="1.8"/></svg>
              </div>
              <h1 class="to-title">Track your order</h1>
              <p class="to-muted">Enter the mobile number you placed your order with — we'll pull up your orders instantly.</p>
            </div>
            <form class="to-form" (ngSubmit)="search()">
              <div class="to-inputrow" [class.to-inputrow--err]="!!searchError()">
                <span class="to-cc">+91</span>
                <input
                  class="to-input"
                  type="tel"
                  inputmode="numeric"
                  autocomplete="tel"
                  maxlength="14"
                  placeholder="10-digit mobile number"
                  [(ngModel)]="mobile"
                  name="mobile"
                  (ngModelChange)="searchError.set(null)"
                />
              </div>
              @if (searchError()) { <div class="to-err">{{ searchError() }}</div> }
              <button class="to-btn" type="submit" [disabled]="loadingList()">
                @if (loadingList()) {
                  <span class="to-spin" aria-hidden="true"></span> Searching…
                } @else {
                  Track my order
                }
              </button>
              <p class="to-hint">🔒 We only show order status — no personal details are revealed.</p>
            </form>
          }

          <!-- ================= STATE 2: order list ================= -->
          @if (view() === 'list') {
            <button class="to-back" type="button" (click)="backToSearch()">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
              Search again
            </button>
            @if (orders().length === 0) {
              <div class="to-empty">
                <div class="to-empty__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
                </div>
                <div class="to-empty__title">No orders found</div>
                <p class="to-muted">We couldn't find any orders for that number. Please check it and try again.</p>
                <button class="to-btn to-btn--ghost" type="button" (click)="backToSearch()">Try another number</button>
              </div>
            } @else {
              <div class="to-listhead">
                <div class="to-listhead__title">Your orders</div>
                <div class="to-listhead__count">{{ totalOrders() }} {{ totalOrders() === 1 ? 'order' : 'orders' }}</div>
              </div>

              @for (o of pagedOrders(); track o.trackingToken; let i = $index) {
                <button class="to-order" type="button" (click)="openOrder(o)"
                        [class.to-order--current]="listPage() === 0 && i === 0">
                  <div class="to-order__icon" [class.to-order__icon--good]="o.delivered">
                    <ng-container [ngTemplateOutlet]="stageIcon" [ngTemplateOutletContext]="{ key: stageKey(o.stage, o.delivered) }"></ng-container>
                  </div>
                  <div class="to-order__main">
                    <div class="to-order__title">
                      {{ o.productSummary }}
                      @if (listPage() === 0 && i === 0) { <span class="to-latest">Latest</span> }
                    </div>
                    <div class="to-order__meta">
                      <span class="to-mono">{{ o.orderCode }}</span>
                      @if (o.placedOn) { <span class="to-muted"> · {{ o.placedOn }}</span> }
                    </div>
                    <div class="to-pill" [class.to-pill--good]="o.delivered">{{ o.statusLabel }}</div>
                  </div>
                  <svg class="to-order__chev" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
                </button>
              }

              @if (totalPages() > 1) {
                <div class="to-pager">
                  <button class="to-pager__btn" type="button" [disabled]="listPage() === 0" (click)="prevPage()" aria-label="Previous page">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
                  </button>
                  <span class="to-pager__info">Page {{ listPage() + 1 }} of {{ totalPages() }}</span>
                  <button class="to-pager__btn" type="button" [disabled]="listPage() >= totalPages() - 1" (click)="nextPage()" aria-label="Next page">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18l6-6-6-6"/></svg>
                  </button>
                </div>
              }
            }
          }

          <!-- ================= STATE 3: tracking detail ================= -->
          @if (view() === 'detail') {
            <button class="to-back" type="button" (click)="backToList()">
              <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
              Back to my orders
            </button>

            @if (loadingDetail()) {
              <!-- Skeleton loader -->
              <div class="to-skel to-skel--line" style="width: 40%"></div>
              <div class="to-skel to-skel--card"></div>
              <div class="to-skel to-skel--stepper"></div>
              <div class="to-skel to-skel--line" style="width: 70%"></div>
              <div class="to-skel to-skel--line" style="width: 60%"></div>
            } @else if (detailError()) {
              <div class="to-empty">
                <div class="to-empty__icon" aria-hidden="true">
                  <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M12 8v5M12 16h.01"/></svg>
                </div>
                <div class="to-empty__title">We couldn't load that order</div>
                <p class="to-muted">Please go back and try again.</p>
              </div>
            } @else if (detail(); as d) {
              @if (d.customerName) { <div class="to-hi">Hi {{ d.customerName }} 👋</div> }

              <!-- Status hero -->
              <div class="to-hero2" [class.to-hero2--good]="d.delivered" [class.to-hero2--bad]="isFailed(d)">
                <div class="to-hero2__icon">
                  <ng-container [ngTemplateOutlet]="stageIcon" [ngTemplateOutletContext]="{ key: stageKey(d.stage, d.delivered) }"></ng-container>
                </div>
                <div class="to-hero2__text">
                  <div class="to-hero2__status">{{ d.statusLabel }}</div>
                  <div class="to-hero2__sub">
                    <span class="to-mono">{{ d.orderCode }}</span>
                    @if (d.estimatedDelivery && !d.delivered) { <span> · Expected by {{ d.estimatedDelivery }}</span> }
                  </div>
                </div>
              </div>

              <!-- Horizontal progress stepper with icons -->
              <div class="to-stepper">
                @for (s of d.timeline; track s.label; let i = $index; let last = $last) {
                  <div class="to-step" [class.to-step--done]="s.reached" [class.to-step--current]="isCurrentStep(d.timeline, i)">
                    <div class="to-stepicon">
                      @switch (i) {
                        @case (0) {
                          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6a1 1 0 0 1 1 1v1h2a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h2V4a1 1 0 0 1 1-1z"/><path d="M9 5h6"/><path d="M8 11h8M8 15h5"/></svg>
                        }
                        @case (1) {
                          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 8l-9-5-9 5 9 5 9-5z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg>
                        }
                        @case (2) {
                          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 6h13v9H1z"/><path d="M14 9h4l3 3v3h-7z"/><circle cx="6" cy="18" r="1.6"/><circle cx="17" cy="18" r="1.6"/></svg>
                        }
                        @default {
                          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
                        }
                      }
                    </div>
                    <div class="to-steptext">{{ s.label }}</div>
                  </div>
                  @if (!last) {
                    <div class="to-stepbar" [class.to-stepbar--done]="s.reached"></div>
                  }
                }
              </div>

              <!-- Where the parcel currently is -->
              @if (latestLocation(d); as loc) {
                <div class="to-nowat" [class.to-nowat--good]="d.delivered">
                  <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 12-9 12s-9-5-9-12a9 9 0 1 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
                  <div>
                    <div class="to-nowat__label">{{ d.delivered ? 'Delivered at' : 'Currently at' }}</div>
                    <div class="to-nowat__loc">{{ loc }}</div>
                  </div>
                </div>
              }

              <!-- Order details (courier / AWB) -->
              @if (d.courierName || d.awb) {
                <div class="to-detailcard">
                  @if (d.courierName) {
                    <div class="to-detailrow"><span class="to-muted">Courier</span><span>{{ d.courierName }}</span></div>
                  }
                  @if (d.awb) {
                    <div class="to-detailrow">
                      <span class="to-muted">Tracking #</span>
                      <span class="to-awb">
                        <span class="to-mono">{{ d.awb }}</span>
                        <button class="to-copy" type="button" (click)="copyAwb(d.awb!)" [attr.aria-label]="'Copy tracking number'">
                          @if (copied()) {
                            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
                          } @else {
                            <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>
                          }
                        </button>
                      </span>
                    </div>
                  }
                </div>
              }

              <!-- Detailed courier journey -->
              @if (d.events.length) {
                <div class="to-journeyhead">
                  <span>Shipment journey</span>
                  <span class="to-journeycount">{{ d.events.length }} updates</span>
                </div>
                <ul class="to-journey">
                  @for (e of visibleEvents(d); track $index; let first = $first) {
                    <li [class.to-journey__first]="first">
                      <span class="to-jdot"></span>
                      <div class="to-jbody">
                        <div class="to-jtop">
                          <span class="to-jstatus">{{ e.instructions || e.status || 'Update' }}</span>
                          @if (e.scanAt) { <span class="to-jtime" [title]="formatScan(e.scanAt)">{{ relativeTime(e.scanAt) }}</span> }
                        </div>
                        @if (e.location) {
                          <div class="to-jloc">
                            <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 12-9 12s-9-5-9-12a9 9 0 1 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
                            {{ e.location }}
                          </div>
                        }
                        @if (e.scanAt) { <div class="to-jexact">{{ formatScan(e.scanAt) }}</div> }
                      </div>
                    </li>
                  }
                </ul>
                @if (d.events.length > collapseAfter && !showAllEvents()) {
                  <button class="to-more" type="button" (click)="showAllEvents.set(true)">
                    Show all {{ d.events.length }} updates
                    <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M6 9l6 6 6-6"/></svg>
                  </button>
                }
              }

              @if (d.trackingUrl) {
                <a class="to-btn to-btn--ghost" [href]="d.trackingUrl" target="_blank" rel="noopener">
                  Track on courier website
                  <svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><path d="M15 3h6v6M10 14L21 3"/></svg>
                </a>
              }
            }
          }
        </div>

        <div class="to-foot">Thank you for choosing <strong>{{ brandName() }}</strong> 🌿</div>
      </div>
    </div>

    <!-- Reusable stage icon (inline SVG via @switch — no innerHTML/sanitizer) -->
    <ng-template #stageIcon let-key="key">
      @switch (key) {
        @case ('delivered') {
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6L9 17l-5-5"/></svg>
        }
        @case ('shipped') {
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 6h13v9H1z"/><path d="M14 9h4l3 3v3h-7z"/><circle cx="6" cy="18" r="1.6"/><circle cx="17" cy="18" r="1.6"/></svg>
        }
        @case ('failed') {
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M15 9l-6 6M9 9l6 6"/></svg>
        }
        @case ('processing') {
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 8l-9-5-9 5 9 5 9-5z"/><path d="M3 8v8l9 5 9-5V8"/><path d="M12 13v8"/></svg>
        }
        @default {
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 3h6a1 1 0 0 1 1 1v1h2a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1h2V4a1 1 0 0 1 1-1z"/><path d="M9 5h6"/><path d="M8 11h8M8 15h5"/></svg>
        }
      }
    </ng-template>
  `,
  styles: [
    `
      .to-wrap {
        min-height: 100vh;
        background:
          radial-gradient(1200px 400px at 50% -10%, #e8f1ec 0%, rgba(232, 241, 236, 0) 60%),
          #f4f7f5;
        display: flex;
        align-items: flex-start;
        justify-content: center;
        padding: 28px 16px 40px;
        font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
        color: #1a1a1a;
        -webkit-font-smoothing: antialiased;
      }
      .to-card {
        width: 100%;
        max-width: 460px;
        background: #fff;
        border-radius: 20px;
        box-shadow: 0 10px 40px rgba(16, 48, 33, 0.12);
        overflow: hidden;
        animation: toRise 0.35s ease both;
      }
      .to-card--wide { max-width: 500px; }
      @keyframes toRise { from { opacity: 0; transform: translateY(8px); } to { opacity: 1; transform: none; } }

      /* Brand header */
      .to-brand {
        display: flex;
        align-items: center;
        gap: 12px;
        padding: 16px 20px;
        background: linear-gradient(120deg, #1f5d3f 0%, #2e7d55 100%);
        color: #fff;
      }
      .to-brand__logo {
        width: 40px; height: 40px;
        border-radius: 10px;
        background: #fff;
        display: flex; align-items: center; justify-content: center;
        flex: none;
      }
      .to-brand__logo img { width: 30px; height: 30px; object-fit: contain; }
      .to-brand__title { font-weight: 700; font-size: 1rem; line-height: 1.1; }
      .to-brand__sub { font-size: 0.75rem; opacity: 0.85; margin-top: 2px; }

      .to-body { padding: 22px 20px 6px; }
      .to-muted { color: #6b7280; }
      .to-mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }

      /* Search hero */
      .to-hero { text-align: center; margin-bottom: 18px; }
      .to-hero__icon {
        width: 60px; height: 60px; margin: 2px auto 12px;
        border-radius: 50%;
        background: #e8f2ec; color: #1f5d3f;
        display: flex; align-items: center; justify-content: center;
      }
      .to-title { font-size: 1.3rem; margin: 0 0 6px; color: #15291f; }
      .to-hero .to-muted { font-size: 0.92rem; line-height: 1.45; }

      .to-form { display: grid; gap: 12px; }
      .to-inputrow {
        display: flex; align-items: stretch;
        border: 1.5px solid #d7dbd9; border-radius: 12px; overflow: hidden;
        transition: border-color 0.15s, box-shadow 0.15s;
      }
      .to-inputrow:focus-within { border-color: #1f5d3f; box-shadow: 0 0 0 3px rgba(31, 93, 63, 0.12); }
      .to-inputrow--err { border-color: #e5484d; }
      .to-cc {
        display: flex; align-items: center; padding: 0 14px;
        background: #f1f4f1; color: #44504a; font-weight: 700;
        border-right: 1px solid #e0e5e2;
      }
      .to-input { flex: 1; border: 0; padding: 14px; font-size: 1.05rem; outline: none; letter-spacing: 0.02em; }
      .to-err { color: #d63939; font-size: 0.88rem; }
      .to-hint { text-align: center; font-size: 0.78rem; color: #8a938d; margin: 4px 0 0; }

      .to-btn {
        display: inline-flex; align-items: center; justify-content: center; gap: 8px;
        width: 100%; text-align: center;
        background: #1f5d3f; color: #fff; text-decoration: none;
        border: 0; padding: 13px; border-radius: 12px;
        font-weight: 700; font-size: 1rem; cursor: pointer;
        transition: transform 0.08s, background 0.15s, box-shadow 0.15s;
        box-shadow: 0 2px 10px rgba(31, 93, 63, 0.2);
      }
      .to-btn:hover:not(:disabled) { background: #1a5236; }
      .to-btn:active:not(:disabled) { transform: translateY(1px); }
      .to-btn:disabled { opacity: 0.7; cursor: default; box-shadow: none; }
      .to-btn--ghost {
        background: #fff; color: #1f5d3f; border: 1.5px solid #cfe0d6; box-shadow: none; margin-top: 4px;
      }
      .to-btn--ghost:hover:not(:disabled) { background: #f3faf6; }
      .to-spin {
        width: 16px; height: 16px; border-radius: 50%;
        border: 2px solid rgba(255,255,255,0.4); border-top-color: #fff;
        animation: toSpin 0.7s linear infinite;
      }
      @keyframes toSpin { to { transform: rotate(360deg); } }

      .to-back {
        display: inline-flex; align-items: center; gap: 4px;
        background: none; border: 0; color: #1f5d3f; font-weight: 600;
        padding: 0 0 14px; cursor: pointer; font-size: 0.9rem;
      }
      .to-back:hover { color: #164029; }

      /* Empty / error states */
      .to-empty { text-align: center; padding: 18px 0 8px; }
      .to-empty__icon {
        width: 56px; height: 56px; margin: 0 auto 12px; border-radius: 50%;
        background: #f1f3f2; color: #9aa0a6; display: flex; align-items: center; justify-content: center;
      }
      .to-empty__title { font-weight: 700; font-size: 1.05rem; margin-bottom: 4px; }

      /* Order list */
      .to-section {
        text-transform: uppercase; letter-spacing: 0.05em; font-size: 0.72rem;
        color: #8a938d; font-weight: 700; margin: 14px 0 8px;
      }
      .to-order {
        display: flex; align-items: center; gap: 12px;
        width: 100%; text-align: left;
        background: #fff; border: 1px solid #e4e8e5; border-radius: 14px;
        padding: 13px 14px; margin-bottom: 10px; cursor: pointer;
        transition: transform 0.1s, box-shadow 0.15s, border-color 0.15s;
      }
      .to-order:hover { box-shadow: 0 6px 18px rgba(16, 48, 33, 0.09); transform: translateY(-1px); }
      .to-order--current { border-color: #bfe0cd; background: #f6fbf8; }
      .to-order__icon {
        width: 42px; height: 42px; border-radius: 11px; flex: none;
        background: #eef4f0; color: #1f5d3f;
        display: flex; align-items: center; justify-content: center;
      }
      .to-order__icon--good { background: #e5f7e9; color: #2fb344; }
      .to-order__main { flex: 1; min-width: 0; }
      .to-order__title {
        font-weight: 600; margin-bottom: 3px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .to-order__meta { font-size: 0.82rem; margin-bottom: 7px; }
      .to-order__chev { color: #c2cac4; flex: none; }
      .to-pill {
        display: inline-block; background: #eef2ef; color: #1f5d3f;
        padding: 3px 10px; border-radius: 999px; font-size: 0.76rem; font-weight: 700;
      }
      .to-pill--good { background: #e5f7e9; color: #2fb344; }

      /* Order-list header + count */
      .to-listhead { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; margin: 6px 0 12px; }
      .to-listhead__title { font-weight: 700; font-size: 1.05rem; color: #15291f; }
      .to-listhead__count {
        font-size: 0.78rem; font-weight: 700; color: #1f5d3f;
        background: #eef4f0; border-radius: 999px; padding: 3px 10px;
      }
      .to-latest {
        display: inline-block; vertical-align: middle; margin-left: 6px;
        font-size: 0.64rem; font-weight: 800; letter-spacing: 0.03em; text-transform: uppercase;
        color: #1f5d3f; background: #dff0e6; border-radius: 5px; padding: 1px 6px;
      }

      /* Pagination */
      .to-pager { display: flex; align-items: center; justify-content: center; gap: 14px; margin: 6px 0 8px; }
      .to-pager__btn {
        display: inline-flex; align-items: center; justify-content: center;
        width: 38px; height: 38px; border-radius: 10px; cursor: pointer;
        background: #fff; color: #1f5d3f; border: 1.5px solid #cfe0d6;
        transition: background 0.15s, opacity 0.15s;
      }
      .to-pager__btn:hover:not(:disabled) { background: #f3faf6; }
      .to-pager__btn:disabled { opacity: 0.4; cursor: default; }
      .to-pager__info { font-size: 0.86rem; color: #5b6b62; font-weight: 600; min-width: 108px; text-align: center; }

      .to-hi { font-size: 1.05rem; font-weight: 600; color: #15291f; margin-bottom: 14px; }

      /* Status hero (detail) */
      .to-hero2 {
        display: flex; align-items: center; gap: 14px;
        background: linear-gradient(120deg, #f3faf6 0%, #eaf4ee 100%);
        border: 1px solid #dcebe2; border-radius: 16px;
        padding: 16px; margin-bottom: 20px;
      }
      .to-hero2--good { background: linear-gradient(120deg, #eafaef 0%, #ddf5e4 100%); border-color: #bfe9c9; }
      .to-hero2--bad { background: linear-gradient(120deg, #fdf0f0 0%, #fbe6e6 100%); border-color: #f3cccc; }
      .to-hero2__icon {
        width: 50px; height: 50px; border-radius: 14px; flex: none;
        background: #1f5d3f; color: #fff;
        display: flex; align-items: center; justify-content: center;
      }
      .to-hero2--good .to-hero2__icon { background: #2fb344; }
      .to-hero2--bad .to-hero2__icon { background: #e5484d; }
      .to-hero2__status { font-size: 1.12rem; font-weight: 800; color: #15291f; line-height: 1.2; }
      .to-hero2__sub { font-size: 0.82rem; color: #5b6b62; margin-top: 3px; }

      /* Horizontal progress stepper */
      .to-stepper { display: flex; align-items: flex-start; margin: 4px 2px 20px; }
      .to-step { display: flex; flex-direction: column; align-items: center; gap: 7px; flex: 0 0 auto; width: 64px; text-align: center; }
      .to-stepicon {
        width: 46px; height: 46px; border-radius: 50%;
        display: flex; align-items: center; justify-content: center;
        background: #f0f2f1; color: #b2bbb6; border: 2px solid #e4e8e5;
        transition: all 0.25s ease;
      }
      .to-step--done .to-stepicon { background: #e5f2ea; color: #1f5d3f; border-color: #1f5d3f; }
      .to-step--current .to-stepicon {
        background: #1f5d3f; color: #fff; border-color: #1f5d3f;
        box-shadow: 0 0 0 0 rgba(31, 93, 63, 0.4);
        animation: toPulse 2s ease-out infinite;
      }
      @keyframes toPulse {
        0% { box-shadow: 0 0 0 0 rgba(31, 93, 63, 0.35); }
        70% { box-shadow: 0 0 0 10px rgba(31, 93, 63, 0); }
        100% { box-shadow: 0 0 0 0 rgba(31, 93, 63, 0); }
      }
      .to-steptext { font-size: 0.72rem; line-height: 1.2; color: #9aa0a6; font-weight: 600; }
      .to-step--done .to-steptext, .to-step--current .to-steptext { color: #1a1a1a; }
      .to-stepbar { flex: 1 1 auto; height: 3px; background: #e4e8e5; border-radius: 2px; margin-top: 21px; min-width: 8px; }
      .to-stepbar--done { background: #1f5d3f; }

      /* Currently-at location banner */
      .to-nowat {
        display: flex; align-items: center; gap: 11px;
        background: #f4faf6; border: 1px solid #d9ece1; border-radius: 12px;
        padding: 12px 14px; margin: 0 0 20px; color: #2c3a33;
      }
      .to-nowat--good { background: #eafaef; border-color: #c3ead0; }
      .to-nowat svg { color: #1f5d3f; flex: none; }
      .to-nowat__label { font-size: 0.74rem; text-transform: uppercase; letter-spacing: 0.04em; color: #7a867f; font-weight: 700; }
      .to-nowat__loc { font-size: 0.95rem; font-weight: 600; color: #15291f; margin-top: 1px; }

      /* Courier detail card */
      .to-detailcard { border: 1px solid #e8ebe9; border-radius: 12px; padding: 12px 14px; margin-bottom: 20px; display: grid; gap: 9px; }
      .to-detailrow { display: flex; justify-content: space-between; align-items: center; gap: 12px; font-size: 0.9rem; }
      .to-awb { display: inline-flex; align-items: center; gap: 8px; }
      .to-copy {
        display: inline-flex; align-items: center; justify-content: center;
        width: 26px; height: 26px; border-radius: 7px; cursor: pointer;
        background: #eef4f0; color: #1f5d3f; border: 1px solid #dceae2;
      }
      .to-copy:hover { background: #e0efe7; }

      /* Shipment journey */
      .to-journeyhead {
        display: flex; align-items: center; justify-content: space-between;
        text-transform: uppercase; letter-spacing: 0.05em; font-size: 0.72rem;
        color: #8a938d; font-weight: 700;
        border-top: 1px solid #eef0ee; padding-top: 16px; margin: 2px 0 12px;
      }
      .to-journeycount { color: #a9b1ab; letter-spacing: 0; text-transform: none; font-weight: 600; }
      .to-journey { list-style: none; margin: 0 0 4px; padding: 0; }
      .to-journey li { position: relative; display: flex; gap: 13px; padding: 0 0 18px; }
      .to-journey li::before {
        content: ''; position: absolute; left: 5px; top: 15px; bottom: -3px; width: 2px; background: #e1e7e3;
      }
      .to-journey li:last-child::before { display: none; }
      .to-jdot { width: 12px; height: 12px; border-radius: 50%; background: #c6cfc9; flex: none; margin-top: 3px; z-index: 1; }
      .to-journey__first .to-jdot { background: #1f5d3f; box-shadow: 0 0 0 4px rgba(31, 93, 63, 0.14); }
      .to-jbody { flex: 1; min-width: 0; }
      .to-jtop { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; }
      .to-jstatus { font-weight: 600; color: #1a1a1a; font-size: 0.93rem; }
      .to-journey__first .to-jstatus { color: #15291f; }
      .to-jtime { color: #9aa0a6; font-size: 0.78rem; white-space: nowrap; flex: none; }
      .to-jloc { display: flex; align-items: center; gap: 5px; color: #5b6b62; font-size: 0.82rem; margin-top: 3px; }
      .to-jloc svg { color: #9aa79e; flex: none; }
      .to-jexact { color: #a9b1ab; font-size: 0.72rem; margin-top: 2px; }
      .to-more {
        display: inline-flex; align-items: center; gap: 5px;
        background: none; border: 0; color: #1f5d3f; font-weight: 600; font-size: 0.88rem;
        cursor: pointer; padding: 2px 0 8px;
      }

      /* Skeleton loaders */
      .to-skel { border-radius: 10px; background: linear-gradient(90deg, #eef1ef 25%, #f6f8f7 37%, #eef1ef 63%); background-size: 400% 100%; animation: toShimmer 1.3s ease infinite; }
      .to-skel--line { height: 14px; margin: 10px 0; }
      .to-skel--card { height: 76px; margin: 6px 0 18px; }
      .to-skel--stepper { height: 56px; margin: 0 0 18px; }
      @keyframes toShimmer { 0% { background-position: 100% 0; } 100% { background-position: -100% 0; } }

      .to-foot { text-align: center; padding: 18px 20px 20px; font-size: 0.82rem; color: #8a938d; border-top: 1px solid #f0f2f1; margin-top: 10px; }
      .to-foot strong { color: #1f5d3f; }

      /* ---- Mobile (<=480px): edge-to-edge card, bigger tap targets ---- */
      @media (max-width: 480px) {
        .to-wrap { padding: 0; align-items: stretch; background: #fff; }
        .to-card, .to-card--wide {
          max-width: 100%; border-radius: 0; box-shadow: none; min-height: 100vh;
          display: flex; flex-direction: column;
        }
        .to-body { padding: 18px 16px 6px; flex: 1; }
        .to-brand { padding: 14px 16px; }
        .to-foot { margin-top: auto; }
        .to-title { font-size: 1.22rem; }
        .to-input { font-size: 16px; } /* 16px prevents iOS zoom-on-focus */
        .to-btn { padding: 15px; } /* larger tap target */
        .to-order { padding: 14px 12px; }
        .to-order__title { font-size: 0.98rem; }
        /* Stepper: let steps breathe and wrap text on narrow screens */
        .to-stepper { justify-content: space-between; }
        .to-step { width: 60px; }
        .to-steptext { font-size: 0.68rem; }
        .to-hero2 { padding: 14px; gap: 12px; }
        .to-hero2__status { font-size: 1.05rem; }
        .to-jtime { font-size: 0.74rem; }
      }

      /* ---- Very small (<=360px): shrink the stepper icons a touch ---- */
      @media (max-width: 360px) {
        .to-stepicon { width: 40px; height: 40px; }
        .to-step { width: 52px; }
        .to-stepbar { margin-top: 18px; }
        .to-steptext { font-size: 0.64rem; }
      }

      /* Respect reduced-motion preferences */
      @media (prefers-reduced-motion: reduce) {
        .to-card, .to-step--current .to-stepicon, .to-skel, .to-spin { animation: none !important; }
      }
    `,
  ],
})
export class TrackOrderComponent {
  private readonly api = inject(ApiClient);

  protected readonly view = signal<'search' | 'list' | 'detail'>('search');
  protected mobile = '';
  protected readonly searchError = signal<string | null>(null);
  protected readonly loadingList = signal(false);
  protected readonly orders = signal<OrderCard[]>([]);

  /** Business/brand display name from settings (defaults until the lookup loads). */
  protected readonly brandName = signal<string>('Shifa Herbal Remedies');

  /** Order-list pagination: fixed page size of 5, current page (0-based). */
  protected readonly pageSize = 5;
  protected readonly listPage = signal(0);
  /** Total orders found for the searched number. */
  protected readonly totalOrders = computed(() => this.orders().length);
  /** Number of pages at {@link #pageSize}. */
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.totalOrders() / this.pageSize)));
  /** The slice of orders shown on the current page. */
  protected readonly pagedOrders = computed(() =>
    this.orders().slice(this.listPage() * this.pageSize, this.listPage() * this.pageSize + this.pageSize),
  );

  protected readonly loadingDetail = signal(false);
  protected readonly detailError = signal(false);
  protected readonly detail = signal<PublicTracking | null>(null);

  /** Whether the full journey is expanded (collapsed to the latest few by default). */
  protected readonly showAllEvents = signal(false);
  /** Copy-to-clipboard "copied!" tick state. */
  protected readonly copied = signal(false);
  /** How many journey events to show before the "show all" toggle. */
  protected readonly collapseAfter = 6;

  /** Advances the order list one page (bounded). */
  protected nextPage(): void {
    if (this.listPage() < this.totalPages() - 1) {
      this.listPage.update((p) => p + 1);
    }
  }

  /** Goes back one page in the order list (bounded). */
  protected prevPage(): void {
    if (this.listPage() > 0) {
      this.listPage.update((p) => p - 1);
    }
  }

  protected search(): void {
    const digits = (this.mobile || '').replace(/\D/g, '');
    if (digits.length !== 10) {
      this.searchError.set('Please enter a valid 10-digit mobile number.');
      return;
    }
    this.searchError.set(null);
    this.loadingList.set(true);
    // Public endpoint — no session, so the auth interceptor sends no token.
    this.api
      .get<TrackByMobile>(`/api/track/by-mobile?mobile=${encodeURIComponent(digits)}`)
      .subscribe({
        next: (res) => {
          this.orders.set(res.orders ?? []);
          this.listPage.set(0);
          if (res.brandName && res.brandName.trim()) {
            this.brandName.set(res.brandName.trim());
          }
          this.loadingList.set(false);
          // Exactly one order → skip straight to its detail.
          if ((res.orders ?? []).length === 1) {
            this.openOrder(res.orders[0]);
          } else {
            this.view.set('list');
          }
        },
        error: () => {
          this.loadingList.set(false);
          this.searchError.set('Something went wrong. Please try again in a moment.');
        },
      });
  }

  protected openOrder(order: OrderCard): void {
    this.view.set('detail');
    this.detail.set(null);
    this.detailError.set(false);
    this.loadingDetail.set(true);
    this.showAllEvents.set(false);
    this.copied.set(false);
    this.api
      .get<PublicTracking>(`/api/track/t/${encodeURIComponent(order.trackingToken)}`)
      .subscribe({
        next: (d) => {
          this.detail.set(d);
          this.loadingDetail.set(false);
        },
        error: () => {
          this.detailError.set(true);
          this.loadingDetail.set(false);
        },
      });
  }

  /**
   * Whether step {@code i} is the CURRENT stage — the last step that has been
   * reached on the happy path (so the stepper can highlight "where it is now").
   */
  protected isCurrentStep(steps: TrackStep[], i: number): boolean {
    if (!steps[i]?.reached) {
      return false;
    }
    const next = steps[i + 1];
    return !next || !next.reached;
  }

  /** Whether the order failed / was returned (drives the red hero styling). */
  protected isFailed(d: PublicTracking): boolean {
    const s = (d.stage || '').toUpperCase();
    return s === 'FAILED_RETURNED' || s === 'CANCELLED' || s === 'REJECTED';
  }

  /**
   * The most recent known courier location (from the newest scan that carries a
   * location), shown under the stepper. Null when there are no scans with a
   * location yet (e.g. order not shipped).
   */
  protected latestLocation(d: PublicTracking): string | null {
    const withLoc = d.events?.find((e) => e.location && e.location.trim());
    return withLoc?.location?.trim() || null;
  }

  /** The journey events shown — latest few, or all when expanded. */
  protected visibleEvents(d: PublicTracking): TrackEvent[] {
    if (this.showAllEvents() || d.events.length <= this.collapseAfter) {
      return d.events;
    }
    return d.events.slice(0, this.collapseAfter);
  }

  /** Copies the AWB to the clipboard and flashes a tick for 2s. */
  protected copyAwb(awb: string): void {
    navigator.clipboard?.writeText(awb).then(
      () => {
        this.copied.set(true);
        setTimeout(() => this.copied.set(false), 2000);
      },
      () => {
        /* clipboard blocked — ignore silently */
      },
    );
  }

  /**
   * A friendly relative time ("just now", "3h ago", "2d ago") from a raw courier
   * scan timestamp. Falls back to the exact formatted time for anything older
   * than a week or unparseable.
   */
  protected relativeTime(raw: string): string {
    const iso = raw.includes('T') ? raw : raw.replace(' ', 'T');
    const then = new Date(iso).getTime();
    if (isNaN(then)) {
      return raw;
    }
    const diffMin = Math.round((Date.now() - then) / 60000);
    if (diffMin < 1) return 'just now';
    if (diffMin < 60) return `${diffMin}m ago`;
    const diffHr = Math.round(diffMin / 60);
    if (diffHr < 24) return `${diffHr}h ago`;
    const diffDay = Math.round(diffHr / 24);
    if (diffDay <= 7) return `${diffDay}d ago`;
    return this.formatScan(raw);
  }

  /**
   * Formats a raw courier scan timestamp (e.g. "2026-10-05 01:53:10") to a
   * friendly "05 Oct 2026, 01:53". Falls back to the raw string when it can't be
   * parsed, so an odd courier format is never hidden.
   */
  protected formatScan(raw: string): string {
    const iso = raw.includes('T') ? raw : raw.replace(' ', 'T');
    const d = new Date(iso);
    if (isNaN(d.getTime())) {
      return raw;
    }
    const date = d.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });
    const time = d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });
    return `${date}, ${time}`;
  }

  /**
   * The icon key for an order's coarse stage, consumed by the {@code #stageIcon}
   * template. Delivered → check, shipped → truck, failed → cross, processing →
   * box, else → clipboard.
   */
  protected stageKey(stage: string | null, delivered: boolean): string {
    if (delivered) {
      return 'delivered';
    }
    const s = (stage || '').toUpperCase();
    if (s === 'SHIPPED') return 'shipped';
    if (s === 'FAILED_RETURNED' || s === 'CANCELLED' || s === 'REJECTED') return 'failed';
    if (s === 'PROCESSING') return 'processing';
    return 'placed';
  }

  protected backToSearch(): void {
    this.view.set('search');
  }

  protected backToList(): void {
    // If the search produced exactly one order there is no meaningful list to
    // return to — go back to the search instead.
    this.view.set(this.orders().length > 1 ? 'list' : 'search');
  }
}
