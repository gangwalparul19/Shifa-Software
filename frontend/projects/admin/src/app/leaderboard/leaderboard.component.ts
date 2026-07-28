import { Component, OnInit, inject, signal } from '@angular/core';
import { Money } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { Leaderboard, MyDayService } from '../dashboard/my-day.service';

/**
 * Sales leaderboard page — this-month ranking by revenue plus the signed-in
 * salesperson's own rank and consecutive-day order streak (light gamification).
 * Moved off the dashboard to its own destination so the dashboard stays focused
 * on the user's own work. ADMIN + SALESPERSON (backend scopes/authorises).
 */
@Component({
  selector: 'admin-leaderboard',
  standalone: true,
  imports: [PageHeaderComponent, StatePanelComponent],
  template: `
    <admin-page-header
      title="Leaderboard"
      subtitle="This month's top performers by revenue."
      [breadcrumbs]="[{ label: 'Leaderboard' }]"
    >
      <button type="button" class="btn btn-icon btn-outline-secondary" (click)="load()" [disabled]="loading()" aria-label="Refresh">
        <i class="ti ti-refresh"></i>
      </button>
    </admin-page-header>

    @if (loading()) {
      <admin-state-panel variant="loading" [card]="true" loadingLabel="Loading leaderboard…" />
    } @else if (error()) {
      <admin-state-panel variant="error" [card]="true" [message]="error()!" (retry)="load()" />
    } @else if (data(); as lb) {
      @if (lb.myRank || lb.myStreakDays > 0) {
        <div class="d-flex flex-wrap gap-2 mb-3">
          @if (lb.myRank) {
            <span class="badge bg-green-lt fs-3 p-2"><i class="ti ti-trophy me-1"></i>You're #{{ lb.myRank }} this month</span>
          }
          @if (lb.myStreakDays > 0) {
            <span class="badge bg-orange-lt fs-3 p-2"><i class="ti ti-flame me-1"></i>{{ lb.myStreakDays }}-day order streak</span>
          }
          <span class="badge bg-secondary-lt fs-3 p-2">{{ money(lb.myRevenue) }} this month</span>
        </div>
      }

      @if (lb.rows.length === 0) {
        <admin-state-panel variant="empty" [card]="true" icon="ti-trophy" title="No sales yet this month"
          message="Rankings appear once orders start coming in this month." />
      } @else {
        <div class="card">
          <div class="list-group list-group-flush">
            @for (r of lb.rows; track r.salespersonId) {
              <div class="list-group-item d-flex align-items-center gap-2" [class.lb-me]="r.isMe">
                <span class="lb-rank" [class.lb-rank--top]="r.rank <= 3">{{ r.rank }}</span>
                <div class="flex-fill min-w-0">
                  <div class="fw-medium text-truncate">
                    {{ r.name }}
                    @if (r.isMe) { <span class="badge bg-green-lt ms-1">You</span> }
                  </div>
                  <div class="text-secondary small">{{ r.orders }} order{{ r.orders === 1 ? '' : 's' }}</div>
                </div>
                <span class="fw-semibold">{{ money(r.revenue) }}</span>
              </div>
            }
          </div>
        </div>
      }
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
    `,
  ],
})
export class LeaderboardComponent implements OnInit {
  private readonly service = inject(MyDayService);

  protected readonly data = signal<Leaderboard | null>(null);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.leaderboard().subscribe({
      next: (d) => {
        this.data.set(d);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the leaderboard. Please try again.');
        this.loading.set(false);
      },
    });
  }

  money(value: Money | number | null | undefined): string {
    const n = Number(value ?? 0);
    return '₹' + (Number.isFinite(n) ? Math.round(n).toLocaleString('en-IN') : '0');
  }
}
