import { Component, OnInit, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { AuthService, Money, Role } from 'core';
import { formatInr } from '../shared/inr.pipe';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PeriodPickerComponent } from '../shared/period-picker.component';
import { PeriodSelection, THIS_MONTH, isUsableSelection } from '../shared/period-selection';
import { StatePanelComponent } from '../shared/state-panel.component';
import { Leaderboard, LeaderboardRow, MyDayService } from '../dashboard/my-day.service';

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
      [subtitle]="'Top performers by revenue · ' + periodText()"
      [breadcrumbs]="[{ label: 'Leaderboard' }]"
    >
      <button type="button" class="btn btn-icon btn-outline-secondary" (click)="load()" [disabled]="loading()" aria-label="Refresh">
        <i class="ti ti-refresh"></i>
      </button>
    </admin-page-header>

    <div class="mb-3">
      <admin-period-picker
        [value]="selection()"
        ariaLabel="Leaderboard period"
        (changed)="choose($event)"
      />
    </div>

    @if (loading() && !data()) {
      <admin-state-panel variant="loading" [card]="true" loadingLabel="Loading leaderboard…" />
    } @else if (error()) {
      <admin-state-panel variant="error" [card]="true" [message]="error()!" (retry)="load()" />
    } @else if (data(); as lb) {
      <div [class.lb-refreshing]="loading()">
        @if (lb.myRank || lb.myStreakDays > 0) {
          <div class="d-flex flex-wrap gap-2 mb-3">
            @if (lb.myRank) {
              <span class="badge bg-green-lt fs-3 p-2"><i class="ti ti-trophy me-1"></i>You're #{{ lb.myRank }} · {{ lb.periodLabel }}</span>
            }
            @if (lb.myStreakDays > 0) {
              <span class="badge bg-orange-lt fs-3 p-2" title="Consecutive days with an order, counted from your whole history"><i class="ti ti-flame me-1"></i>{{ lb.myStreakDays }}-day order streak</span>
            }
            @if (lb.myRank) {
              <span class="badge bg-secondary-lt fs-3 p-2">{{ money(lb.myRevenue) }} · {{ lb.periodLabel }}</span>
            }
          </div>
        }

        @if (lb.rows.length === 0) {
          <admin-state-panel variant="empty" [card]="true" icon="ti-trophy"
            title="No sales in this period"
            [message]="'Nothing to rank for ' + lb.periodLabel + '. Rankings appear once orders come in, or try a different period above.'" />
        } @else {
          <div class="card">
            <div class="list-group list-group-flush">
              @for (r of lb.rows; track r.salespersonId) {
                <div
                  class="list-group-item d-flex align-items-center gap-2"
                  [class.lb-me]="r.isMe"
                  [class.lb-click]="canDrill()"
                  [attr.role]="canDrill() ? 'button' : null"
                  [attr.tabindex]="canDrill() ? 0 : null"
                  (click)="canDrill() && viewOrders(r, lb)"
                  (keydown.enter)="canDrill() && viewOrders(r, lb)"
                >
                  <span class="lb-rank" [class.lb-rank--top]="r.rank <= 3">{{ r.rank }}</span>
                  <div class="flex-fill min-w-0">
                    <div class="fw-medium text-truncate">
                      {{ r.name }}
                      @if (r.isMe) { <span class="badge bg-green-lt ms-1">You</span> }
                    </div>
                    <div class="text-secondary small">{{ r.orders }} order{{ r.orders === 1 ? '' : 's' }}</div>
                  </div>
                  <span class="fw-semibold">{{ money(r.revenue) }}</span>
                  @if (canDrill()) {
                    <i class="ti ti-chevron-right text-secondary"></i>
                  }
                </div>
              }
            </div>
          </div>
          <p class="text-secondary small mt-2 mb-0">
            Revenue and order counts leave out cancelled, rejected and payment-rejected orders.
            Only salespeople are ranked.
          </p>
        }
      </div>
    }
  `,
  styles: [
    `
      .lb-rank {
        flex: 0 0 auto;
        width: 30px;
        height: 30px;
        border-radius: 50%;
        display: inline-grid;
        place-items: center;
        font-weight: 800;
        font-size: 0.85rem;
        background: #eef2f0;
        color: #64726b;
      }
      .lb-rank--top {
        background: var(--shifa-gold, #c9a227);
        color: #fff;
      }
      .lb-me {
        background: var(--shifa-green-050, #f2f9f5);
      }
      .lb-click {
        cursor: pointer;
        transition: background-color 0.12s ease;
      }
      .lb-click:hover,
      .lb-click:focus-visible {
        background: var(--shifa-green-050, #f2f9f5);
        outline: none;
      }
      .lb-refreshing {
        opacity: 0.55;
        pointer-events: none;
        transition: opacity 0.12s ease;
      }
    `,
  ],
})
export class LeaderboardComponent implements OnInit {
  private readonly service = inject(MyDayService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  protected readonly data = signal<Leaderboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

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

  /**
   * Whether leaderboard rows drill into a salesperson's orders — ADMIN only.
   * A salesperson can't view other people's orders (the backend scopes them),
   * so their rows stay non-interactive.
   */
  canDrill(): boolean {
    return this.auth.hasAnyRole(Role.ADMIN);
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

  money(value: Money | number | null | undefined): string {
    return formatInr(value, 0);
  }
}
