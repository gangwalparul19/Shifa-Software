import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService, Money, Role } from 'core';
import { formatCompactInr, formatInr } from '../shared/inr.pipe';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PeriodPickerComponent } from '../shared/period-picker.component';
import { PeriodSelection, THIS_MONTH, isUsableSelection } from '../shared/period-selection';
import { saveBlob } from '../shared/blob-download.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { Leaderboard, LeaderboardExportFormat, LeaderboardRow, MyDayService } from '../dashboard/my-day.service';

/**
 * Sales leaderboard page — ranking by revenue for a chosen period (this month by default) plus the
 * signed-in salesperson's own rank and consecutive-day order streak (light gamification).
 * Moved off the dashboard to its own destination so the dashboard stays focused
 * on the user's own work. ADMIN + SALESPERSON (backend scopes/authorises).
 */
@Component({
  selector: 'admin-leaderboard',
  standalone: true,
  imports: [PageHeaderComponent, PeriodPickerComponent, StatePanelComponent],
  template: `
    <admin-page-header
      title="Leaderboard"
      subtitle="See who is leading the team and how your sales are moving"
      [breadcrumbs]="[{ label: 'Leaderboard' }]"
    >
      <div class="btn-list">
        <button
          type="button"
          class="btn btn-sm btn-outline-success"
          (click)="exportLeaderboard('xlsx')"
          [disabled]="exporting() !== null || loading() || !data()"
          title="Download the complete ranking as Excel"
        >
          @if (exporting() === 'xlsx') { <span class="spinner-border spinner-border-sm me-1"></span> }
          <i class="ti ti-file-spreadsheet me-1" aria-hidden="true"></i> Excel
        </button>
        <button
          type="button"
          class="btn btn-sm btn-outline-danger"
          (click)="exportLeaderboard('pdf')"
          [disabled]="exporting() !== null || loading() || !data()"
          title="Download the complete ranking as PDF"
        >
          @if (exporting() === 'pdf') { <span class="spinner-border spinner-border-sm me-1"></span> }
          <i class="ti ti-file-type-pdf me-1" aria-hidden="true"></i> PDF
        </button>
        <button
          type="button"
          class="btn btn-icon btn-outline-secondary"
          (click)="load()"
          [disabled]="loading()"
          aria-label="Refresh leaderboard"
          title="Refresh leaderboard"
        >
          <i class="ti ti-refresh"></i>
        </button>
      </div>
    </admin-page-header>

    <section class="lb-period card mb-3" aria-labelledby="leaderboard-period-title">
      <div class="card-body">
        <div class="lb-period__top">
          <div class="lb-period__intro">
            <span class="lb-eyebrow"><i class="ti ti-calendar-stats me-1" aria-hidden="true"></i>Ranking period</span>
            <strong id="leaderboard-period-title">{{ periodText() }}</strong>
            <span class="text-secondary small">Revenue and order counts are calculated for this period.</span>
          </div>
          @if (loading() && data()) {
            <span class="lb-updating"><span class="spinner-border spinner-border-sm me-1"></span>Updating…</span>
          }
        </div>
        <admin-period-picker
          [value]="selection()"
          [disabled]="loading()"
          ariaLabel="Leaderboard period"
          (changed)="choose($event)"
        />
      </div>
    </section>

    @if (loading() && !data()) {
      <admin-state-panel variant="loading" [card]="true" loadingLabel="Loading leaderboard…" />
    } @else if (error()) {
      <admin-state-panel variant="error" [card]="true" [message]="error()!" (retry)="load()" />
    } @else if (data(); as lb) {
      <div class="lb-content" [class.lb-refreshing]="loading()">
        <section class="lb-standing card mb-3" aria-labelledby="leaderboard-standing-title">
          <div class="lb-standing__glow" aria-hidden="true"></div>
          <div class="lb-standing__content">
            <div class="lb-standing__heading">
              <span class="lb-eyebrow lb-eyebrow--light"><i class="ti ti-sparkles me-1" aria-hidden="true"></i>Your standing</span>
              <h2 id="leaderboard-standing-title">Keep the momentum going</h2>
              <p>{{ lb.periodLabel }} · Rankings are ordered by sales revenue.</p>
            </div>
            <div class="lb-standing__badge" [class.lb-standing__badge--unranked]="!lb.myRank">
              @if (lb.myRank) {
                <span class="lb-standing__badge-label">Your rank</span>
                <strong>#{{ lb.myRank }}</strong>
                <span>of {{ lb.rows.length }} ranked salesperson{{ lb.rows.length === 1 ? '' : 's' }}</span>
              } @else {
                <i class="ti ti-chart-no-axes-column-increasing" aria-hidden="true"></i>
                <strong>Not ranked yet</strong>
                <span>Make your first sale in this period</span>
              }
            </div>
          </div>

          <div class="lb-metrics" aria-label="Your leaderboard metrics">
            <div class="lb-metric">
              <span class="lb-metric__icon lb-metric__icon--green"><i class="ti ti-cash" aria-hidden="true"></i></span>
              <div>
                <span class="lb-metric__label">Period revenue</span>
                <strong [title]="lb.myRank ? money(lb.myRevenue) : 'No revenue in this period'">
                  {{ lb.myRank ? compactMoney(lb.myRevenue) : '—' }}
                </strong>
              </div>
            </div>
            <div class="lb-metric">
              <span class="lb-metric__icon lb-metric__icon--gold"><i class="ti ti-flame" aria-hidden="true"></i></span>
              <div>
                <span class="lb-metric__label">Order streak</span>
                <strong>{{ lb.myStreakDays }} <small>day{{ lb.myStreakDays === 1 ? '' : 's' }}</small></strong>
                <span class="lb-metric__hint">All-time history</span>
              </div>
            </div>
            <div class="lb-metric lb-metric--tip">
              <span class="lb-metric__icon lb-metric__icon--blue"><i class="ti ti-target-arrow" aria-hidden="true"></i></span>
              <div>
                <span class="lb-metric__label">Focus</span>
                <strong>{{ lb.myRank ? 'Climb higher' : 'Start today' }}</strong>
                <span class="lb-metric__hint">Every order counts</span>
              </div>
            </div>
          </div>
        </section>

        @if (lb.rows.length === 0) {
          <admin-state-panel
            variant="empty"
            [card]="true"
            icon="ti-trophy"
            title="No sales in this period"
            [message]="'Nothing to rank for ' + lb.periodLabel + '. Rankings appear once orders come in, or try a different period above.'"
          />
        } @else {
          <section class="lb-podium-section mb-3" aria-labelledby="leaderboard-top-title">
            <div class="lb-section-heading">
              <div>
                <span class="lb-eyebrow">Top performers</span>
                <h2 id="leaderboard-top-title">Leading the way</h2>
              </div>
              <span class="lb-section-heading__period">{{ lb.periodLabel }}</span>
            </div>
            <div class="lb-podium">
              @for (r of lb.rows.slice(0, 3); track r.salespersonId) {
                <article class="lb-podium__card" [class]="podiumClass(r.rank)" [class.lb-podium__card--me]="r.isMe">
                  <div class="lb-podium__medal" aria-hidden="true">
                    @if (r.rank === 1) { <i class="ti ti-crown"></i> }
                    @else if (r.rank === 2) { <i class="ti ti-medal"></i> }
                    @else { <i class="ti ti-award"></i> }
                  </div>
                  <span class="lb-podium__rank">#{{ r.rank }}</span>
                  <span class="lb-avatar" [class.lb-avatar--me]="r.isMe">{{ initials(r.name) }}</span>
                  <div class="lb-podium__name" [title]="r.name">{{ r.name }}</div>
                  @if (r.isMe) { <span class="badge tone-green lb-you">You</span> }
                  <strong class="lb-podium__revenue" [title]="money(r.revenue)">{{ compactMoney(r.revenue) }}</strong>
                  <span class="lb-podium__orders">{{ r.orders }} order{{ r.orders === 1 ? '' : 's' }}</span>
                </article>
              }
            </div>
          </section>

          <section class="lb-ranking card" aria-labelledby="leaderboard-ranking-title">
            <div class="card-header lb-ranking__header">
              <div>
                <h2 class="card-title" id="leaderboard-ranking-title">Full ranking</h2>
                <div class="text-secondary small">{{ lb.rows.length }} salesperson{{ lb.rows.length === 1 ? '' : 's' }} · highest revenue first</div>
              </div>
              @if (canDrill()) {
                <span class="lb-ranking__hint"><i class="ti ti-click me-1" aria-hidden="true"></i>Click a row to view orders</span>
              }
            </div>
            <div class="lb-ranking__list" role="list">
              @for (r of lb.rows; track r.salespersonId) {
                <div
                  class="lb-row"
                  [class.lb-row--me]="r.isMe"
                  [class.lb-row--clickable]="canDrill()"
                  [attr.role]="canDrill() ? 'button' : 'listitem'"
                  [attr.tabindex]="canDrill() ? 0 : null"
                  [attr.aria-label]="rowAriaLabel(r, lb)"
                  (click)="canDrill() && viewOrders(r, lb)"
                  (keydown.enter)="canDrill() && viewOrders(r, lb)"
                  (keydown.space)="canDrill() && onRowSpace($event, r, lb)"
                >
                  <span class="lb-row__rank" [class.lb-row__rank--top]="r.rank <= 3">{{ r.rank }}</span>
                  <span class="lb-avatar lb-avatar--small" [class.lb-avatar--me]="r.isMe">{{ initials(r.name) }}</span>
                  <div class="lb-row__main">
                    <div class="lb-row__name">
                      <strong>{{ r.name }}</strong>
                      @if (r.isMe) { <span class="badge tone-green">You</span> }
                    </div>
                    <div class="lb-row__meta">{{ r.orders }} order{{ r.orders === 1 ? '' : 's' }} · {{ revenueShare(r, lb) }}% of leader</div>
                    <div class="lb-progress" aria-hidden="true"><span [style.width.%]="revenueBar(r, lb)"></span></div>
                  </div>
                  <div class="lb-row__value" [title]="money(r.revenue)">
                    <strong>{{ compactMoney(r.revenue) }}</strong>
                    <span>revenue</span>
                  </div>
                  @if (canDrill()) { <i class="ti ti-chevron-right lb-row__chevron" aria-hidden="true"></i> }
                </div>
              }
            </div>
            <div class="lb-ranking__footer">
              <i class="ti ti-info-circle me-1" aria-hidden="true"></i>
              Revenue and order counts leave out cancelled, rejected and payment-rejected orders. Only salespeople are ranked.
              Your order streak is calculated from your whole order history, independent of the selected period.
            </div>
          </section>
        }
      </div>
    }
  `,
  styles: [
    `
      :host { display: block; }
      .lb-period { border: 1px solid #e2ebe5; box-shadow: 0 2px 8px rgba(18, 62, 42, 0.04); }
      .lb-period__top { display: flex; align-items: flex-start; justify-content: space-between; gap: 1rem; margin-bottom: 0.85rem; }
      .lb-period__intro { display: flex; flex-wrap: wrap; align-items: baseline; gap: 0.45rem 0.8rem; min-width: 0; }
      .lb-period__intro strong { color: #173e2b; font-size: 0.95rem; }
      .lb-period__intro .text-secondary { flex-basis: 100%; }
      .lb-eyebrow { display: block; color: #5e7568; font-size: 0.7rem; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; }
      .lb-eyebrow--light { color: rgba(255, 255, 255, 0.76); }
      .lb-updating { flex: 0 0 auto; color: #5e7568; font-size: 0.78rem; white-space: nowrap; }

      .lb-standing { position: relative; overflow: hidden; border: 0; color: #fff; background: linear-gradient(125deg, #164b32 0%, #23734c 62%, #2d8a5c 100%); box-shadow: 0 10px 24px rgba(21, 87, 54, 0.18); }
      .lb-standing__glow { position: absolute; width: 250px; height: 250px; right: -65px; top: -120px; border-radius: 50%; background: rgba(255, 255, 255, 0.1); }
      .lb-standing__glow::after { content: ''; position: absolute; width: 150px; height: 150px; right: 65px; top: 110px; border-radius: 50%; border: 1px solid rgba(255, 255, 255, 0.18); }
      .lb-standing__content { position: relative; display: flex; align-items: center; justify-content: space-between; gap: 1rem; padding: 1.35rem 1.35rem 1.05rem; }
      .lb-standing__heading { min-width: 0; }
      .lb-standing h2 { margin: 0.2rem 0 0.3rem; color: #fff; font-size: clamp(1.25rem, 3vw, 1.65rem); letter-spacing: -0.02em; }
      .lb-standing p { margin: 0; color: rgba(255, 255, 255, 0.8); font-size: 0.82rem; }
      .lb-standing__badge { position: relative; flex: 0 0 auto; min-width: 126px; padding: 0.75rem 1rem; border: 1px solid rgba(255, 255, 255, 0.28); border-radius: 14px; text-align: center; background: rgba(255, 255, 255, 0.13); }
      .lb-standing__badge-label { display: block; color: rgba(255, 255, 255, 0.72); font-size: 0.67rem; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; }
      .lb-standing__badge strong { display: block; margin: 0.1rem 0; color: #fff; font-size: 1.65rem; line-height: 1; }
      .lb-standing__badge span:last-child { display: block; color: rgba(255, 255, 255, 0.78); font-size: 0.68rem; }
      .lb-standing__badge--unranked { padding: 0.8rem 0.9rem; }
      .lb-standing__badge--unranked i { display: block; margin-bottom: 0.25rem; color: #f5d889; font-size: 1.2rem; }
      .lb-standing__badge--unranked strong { font-size: 0.85rem; }

      .lb-metrics { position: relative; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0.55rem; padding: 0 1.35rem 1.35rem; }
      .lb-metric { display: flex; align-items: center; gap: 0.7rem; min-width: 0; padding: 0.75rem; border: 1px solid rgba(255, 255, 255, 0.16); border-radius: 11px; background: rgba(8, 43, 28, 0.2); }
      .lb-metric__icon { display: grid; place-items: center; width: 34px; height: 34px; flex: 0 0 auto; border-radius: 10px; font-size: 1rem; }
      .lb-metric__icon--green { color: #a9efc3; background: rgba(169, 239, 195, 0.15); }
      .lb-metric__icon--gold { color: #ffe39a; background: rgba(255, 227, 154, 0.15); }
      .lb-metric__icon--blue { color: #b9dcff; background: rgba(185, 220, 255, 0.14); }
      .lb-metric__label, .lb-metric__hint { display: block; color: rgba(255, 255, 255, 0.68); font-size: 0.68rem; line-height: 1.25; }
      .lb-metric strong { display: block; margin-top: 0.15rem; color: #fff; font-size: 1.08rem; line-height: 1.1; white-space: nowrap; }
      .lb-metric strong small { color: rgba(255, 255, 255, 0.72); font-size: 0.72rem; font-weight: 600; }
      .lb-section-heading { display: flex; align-items: end; justify-content: space-between; gap: 1rem; margin: 0 0 0.8rem; }
      .lb-section-heading h2 { margin: 0.15rem 0 0; color: #173e2b; font-size: 1.1rem; }
      .lb-section-heading__period { color: #6b7d72; font-size: 0.75rem; }

      .lb-podium { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); align-items: end; gap: 0.7rem; }
      .lb-podium__card { position: relative; display: flex; min-width: 0; min-height: 184px; flex-direction: column; align-items: center; justify-content: flex-end; padding: 1.1rem 0.6rem 0.9rem; border: 1px solid #e4ebe6; border-radius: 14px; text-align: center; background: linear-gradient(180deg, #fff 0%, #f8fbf9 100%); box-shadow: 0 3px 9px rgba(25, 65, 45, 0.05); }
      .lb-podium__card.rank-1 { min-height: 204px; border-color: #dfc36b; background: linear-gradient(180deg, #fffdf4 0%, #fffaf0 100%); box-shadow: 0 7px 18px rgba(189, 147, 34, 0.16); }
      .lb-podium__card.rank-2 { border-color: #d6dde1; }
      .lb-podium__card.rank-3 { border-color: #e0c8ab; }
      .lb-podium__card--me { outline: 2px solid #2d865a; outline-offset: 2px; }
      .lb-podium__medal { position: absolute; top: 0.55rem; left: 0.7rem; color: #c39b23; font-size: 1.05rem; }
      .lb-podium__card.rank-2 .lb-podium__medal { color: #84939b; }
      .lb-podium__card.rank-3 .lb-podium__medal { color: #b37742; }
      .lb-podium__rank { position: absolute; top: 0.6rem; right: 0.7rem; color: #718077; font-size: 0.7rem; font-weight: 800; }
      .lb-avatar { display: grid; place-items: center; width: 52px; height: 52px; margin: 0.5rem 0 0.5rem; border: 3px solid #dcefe2; border-radius: 50%; color: #1f5d3f; background: #edf7f0; font-size: 0.95rem; font-weight: 800; }
      .lb-avatar--small { width: 38px; height: 38px; margin: 0; border-width: 2px; font-size: 0.76rem; }
      .lb-avatar--me { border-color: #3b9968; background: #dff2e6; }
      .lb-podium__name { max-width: 100%; overflow: hidden; color: #1c3528; font-size: 0.86rem; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
      .lb-you { margin-top: 0.25rem; font-size: 0.62rem; }
      .lb-podium__revenue { margin-top: 0.5rem; color: #164632; font-size: 1.08rem; }
      .lb-podium__orders { color: #687a70; font-size: 0.72rem; }

      .lb-ranking { overflow: hidden; border: 1px solid #e1e9e4; box-shadow: 0 3px 10px rgba(25, 65, 45, 0.04); }
      .lb-ranking__header { display: flex; align-items: center; justify-content: space-between; gap: 1rem; }
      .lb-ranking__header .card-title { margin: 0; color: #173e2b; font-size: 1.02rem; }
      .lb-ranking__hint { color: #6a7b71; font-size: 0.73rem; white-space: nowrap; }
      .lb-ranking__list { padding: 0.35rem 0; }
      .lb-row { display: grid; grid-template-columns: 34px 38px minmax(0, 1fr) auto 20px; align-items: center; gap: 0.7rem; min-height: 76px; padding: 0.75rem 1rem; border-bottom: 1px solid #edf2ee; background: #fff; }
      .lb-row:last-child { border-bottom: 0; }
      .lb-row--me { background: #f1faf4; }
      .lb-row--clickable { cursor: pointer; transition: background-color 0.12s ease, transform 0.12s ease; }
      .lb-row--clickable:hover, .lb-row--clickable:focus-visible { background: #eaf6ee; outline: none; }
      .lb-row--clickable:focus-visible { box-shadow: inset 0 0 0 2px #3b9968; }
      .lb-row__rank { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; color: #68766e; background: #f0f3f1; font-size: 0.78rem; font-weight: 800; }
      .lb-row__rank--top { color: #8d6a0c; background: #fff2bb; }
      .lb-row__main { min-width: 0; }
      .lb-row__name { display: flex; align-items: center; gap: 0.45rem; min-width: 0; color: #1c3528; }
      .lb-row__name strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .lb-row__name .badge { flex: 0 0 auto; font-size: 0.6rem; }
      .lb-row__meta { margin-top: 0.13rem; color: #74847a; font-size: 0.7rem; }
      .lb-progress { height: 5px; margin-top: 0.45rem; overflow: hidden; border-radius: 99px; background: #edf2ee; }
      .lb-progress span { display: block; height: 100%; min-width: 3px; border-radius: inherit; background: linear-gradient(90deg, #3f9867, #1f5d3f); }
      .lb-row__value { min-width: 70px; text-align: right; }
      .lb-row__value strong { display: block; color: #164632; font-size: 0.92rem; white-space: nowrap; }
      .lb-row__value span { display: block; margin-top: 0.1rem; color: #7a897f; font-size: 0.65rem; }
      .lb-row__chevron { color: #87958d; font-size: 1rem; }
      .lb-ranking__footer { padding: 0.75rem 1rem; border-top: 1px solid #e6eee8; color: #6a7b71; background: #fbfdfb; font-size: 0.73rem; line-height: 1.45; }
      .lb-content { min-width: 0; }
      .lb-refreshing { opacity: 0.55; pointer-events: none; transition: opacity 0.12s ease; }

      @media (max-width: 767.98px) {
        .lb-standing__content { align-items: flex-start; padding: 1.1rem 1rem 0.85rem; }
        .lb-standing__badge { min-width: 112px; padding-inline: 0.65rem; }
        .lb-standing__badge strong { font-size: 1.35rem; }
        .lb-metrics { grid-template-columns: 1fr; padding: 0 1rem 1rem; }
        .lb-metric { min-height: 58px; }
        .lb-podium { gap: 0.45rem; }
        .lb-podium__card { min-height: 166px; padding-inline: 0.35rem; }
        .lb-podium__card.rank-1 { min-height: 184px; }
        .lb-podium__revenue { font-size: 0.93rem; }
        .lb-ranking__hint { display: none; }
        .lb-row { grid-template-columns: 28px 34px minmax(0, 1fr) auto 14px; gap: 0.5rem; padding: 0.7rem; }
        .lb-avatar--small { width: 34px; height: 34px; font-size: 0.68rem; }
        .lb-row__value { min-width: 60px; }
        .lb-row__value strong { font-size: 0.82rem; }
      }
      @media (max-width: 430px) {
        .lb-period__top { display: block; }
        .lb-updating { display: block; margin-top: 0.45rem; }
        .lb-standing__content { display: block; }
        .lb-standing__badge { display: inline-block; margin-top: 0.85rem; }
        .lb-podium__card { min-height: 154px; }
        .lb-podium__card.rank-1 { min-height: 170px; }
        .lb-podium__medal { left: 0.45rem; font-size: 0.85rem; }
        .lb-podium__rank { right: 0.45rem; }
        .lb-avatar { width: 44px; height: 44px; }
        .lb-podium__name { font-size: 0.76rem; }
        .lb-row { grid-template-columns: 26px 32px minmax(0, 1fr) auto; }
        .lb-row__chevron { display: none; }
        .lb-row__value { min-width: 54px; }
        .lb-row__meta { font-size: 0.65rem; }
      }
    `,
  ],
})
export class LeaderboardComponent implements OnInit {
  private readonly service = inject(MyDayService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);
  private readonly toasts = inject(ToastService);

  protected readonly data = signal<Leaderboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly exporting = signal<LeaderboardExportFormat | null>(null);

  /** The period being shown: this month until the user picks another. */
  protected readonly selection = signal<PeriodSelection>(THIS_MONTH);

  /** Numbers the requests so a slow answer for an earlier choice cannot overwrite a newer one. */
  private requestId = 0;

  ngOnInit(): void {
    this.load();
  }

  /** The period in words, from the last answer (the server's label) or the chosen option. */
  protected periodText(): string {
    return this.data()?.periodLabel ?? 'This month';
  }

  /** Picks a period and reloads the ranking for it. */
  choose(selection: PeriodSelection): void {
    this.selection.set(selection);
    this.load();
  }

  /** Whether leaderboard rows drill into a salesperson's orders — ADMIN only. */
  canDrill(): boolean {
    return this.auth.hasAnyRole(Role.ADMIN);
  }

  /** Names a salesperson from their display name for the avatar. */
  initials(name: string | null | undefined): string {
    const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return '?';
    if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
    return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
  }

  compactMoney(value: Money | number | null | undefined): string {
    return formatCompactInr(value);
  }

  money(value: Money | number | null | undefined): string {
    return formatInr(value, 0);
  }

  podiumClass(rank: number): string {
    return `rank-${rank}`;
  }

  /** Percentage of the highest revenue used to render the comparison bar. */
  revenueBar(row: LeaderboardRow, board: Leaderboard): number {
    const leader = Number(board.rows[0]?.revenue ?? 0);
    const revenue = Number(row.revenue ?? 0);
    if (!Number.isFinite(leader) || leader <= 0 || !Number.isFinite(revenue)) return 0;
    return Math.min(100, Math.max(0, (revenue / leader) * 100));
  }

  revenueShare(row: LeaderboardRow, board: Leaderboard): string {
    return this.revenueBar(row, board).toFixed(0);
  }

  rowAriaLabel(row: LeaderboardRow, board: Leaderboard): string {
    const action = this.canDrill() ? '. Activate to view orders.' : '';
    return `${row.name}, rank ${row.rank}, ${row.orders} order${row.orders === 1 ? '' : 's'}, ${this.money(row.revenue)} revenue in ${board.periodLabel}${action}`;
  }

  onRowSpace(event: Event, row: LeaderboardRow, board: Leaderboard): void {
    event.preventDefault();
    this.viewOrders(row, board);
  }

  /** Downloads the complete ranking for the currently selected period. */
  exportLeaderboard(format: LeaderboardExportFormat): void {
    if (this.exporting()) {
      return;
    }
    const selection = this.selection();
    if (!isUsableSelection(selection)) {
      this.toasts.error('Choose a valid start and end date first.');
      return;
    }
    this.exporting.set(format);
    this.service.exportLeaderboard(format, selection).subscribe({
      next: (result) => {
        saveBlob(result.blob, result.filename, `leaderboard.${format}`);
        this.exporting.set(null);
        this.toasts.success(`Complete leaderboard ${format.toUpperCase()} downloaded.`);
      },
      error: () => {
        this.exporting.set(null);
        this.toasts.error(`Could not download the complete leaderboard ${format.toUpperCase()}.`);
      },
    });
  }

  /** Navigates to the Orders page scoped to this salesperson's orders in the period shown. */
  viewOrders(row: LeaderboardRow, board: Leaderboard): void {
    const queryParams: Record<string, string | number> = {
      createdBy: row.salespersonId,
      name: row.name,
    };
    // "Overall" has no dates; every other period narrows the Orders page to the same days.
    if (board.from && board.to) {
      queryParams['from'] = board.from;
      queryParams['to'] = board.to;
    }
    void this.router.navigate(['/orders'], { queryParams });
  }

  load(): void {
    const selection = this.selection();
    if (!isUsableSelection(selection)) {
      return; // a half-typed custom range: wait for a valid one
    }
    const request = ++this.requestId;
    this.loading.set(true);
    this.error.set(null);
    this.service.leaderboard(selection).subscribe({
      next: (d) => {
        if (request !== this.requestId) {
          return;
        }
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        if (request !== this.requestId) {
          return;
        }
        this.error.set('Could not load the leaderboard. Please try again.');
        this.loading.set(false);
      },
    });
  }
}
