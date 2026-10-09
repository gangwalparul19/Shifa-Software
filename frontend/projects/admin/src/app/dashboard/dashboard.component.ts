import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { AuthService, Role } from 'core';
import { AdminEventsService } from './admin-events.service';
import { AdminChannelOverviewComponent } from './admin-channel-overview.component';
import { DeliveryPartnerOverviewComponent } from './delivery-partner-overview.component';
import { TeamsOverviewWidgetComponent } from './teams-overview-widget.component';
import { CountUpDirective } from '../shared/count-up.directive';
import { PageHeaderComponent } from '../shared/page-header.component';
import { DashboardService } from './dashboard.service';
import { MyDay, MyDayService, ReorderDueCustomer, WinBackCustomer } from './my-day.service';
import { TeamPerformance, TeamPerformanceService } from '../team/team-performance.service';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';
import { humanizeStatus } from '../shared/status-badge.component';
import { ORDER_STATUS_GROUPS } from '../orders/order-status-groups';
import { ChannelMarginReport, OwnerSnapshot, RoleDashboardSummary } from './dashboard.model';

/** A single labelled count derived from a role-summary status map. */
interface StatusCount {
  key: string;
  label: string;
  value: number;
}

/**
 * One actionable row in the salesperson "My tasks" inbox: a reorder-due nudge
 * or a win-back follow-up, folded into a single ranked list (most urgent first)
 * so the rep has one place to see "who to call today" instead of two cards.
 */
interface TaskInboxItem {
  kind: 'reorder' | 'winback';
  mobile: string;
  customerName: string | null;
  /** Short reason label, e.g. "5d overdue" or "no order in 72d". */
  reason: string;
  /** Higher = more urgent; used purely for ordering. */
  priority: number;
  icon: string;
  accent: string;
  /** Original source rows so the WhatsApp/tel helpers get the right shape. */
  reorderSource?: ReorderDueCustomer;
  winbackSource?: WinBackCustomer;
}

/**
 * A lifecycle-stage-group count for the salesperson/team-lead "By status"
 * tiles: the many raw statuses folded into the business-facing groups the
 * Orders page uses, each with a colour accent + icon so the section reads as a
 * clean, scannable KPI row instead of a flat monochrome list.
 */
interface StageGroupCount {
  key: string;
  label: string;
  value: number;
  accent: string;
  accentSoft: string;
  icon: string;
}

/** Colour accent + icon for each QuikShip-aligned lifecycle stage group. */
const STAGE_GROUP_STYLE: Record<string, { accent: string; accentSoft: string; icon: string }> = {
  PENDING_APPROVAL: { accent: '#f59f00', accentSoft: '#fdf1da', icon: 'ti ti-clock' },
  PROCESSING: { accent: '#0ca678', accentSoft: '#e3f7f0', icon: 'ti ti-box' },
  SHIPPED: { accent: '#206bc4', accentSoft: '#e7f0fb', icon: 'ti ti-truck' },
  DELIVERED: { accent: '#2fb344', accentSoft: '#e5f6e8', icon: 'ti ti-circle-check' },
  FAILED_RETURNED: { accent: '#d63939', accentSoft: '#fbe7e7', icon: 'ti ti-alert-triangle' },
  CANCELLED: { accent: '#868e96', accentSoft: '#f1f3f5', icon: 'ti ti-ban' },
  REJECTED: { accent: '#d63939', accentSoft: '#fbe7e7', icon: 'ti ti-circle-x' },
};

/** Fallback style for any group key missing from the map (defensive — never crash). */
const DEFAULT_STAGE_STYLE = { accent: '#868e96', accentSoft: '#f1f3f5', icon: 'ti ti-circle' };

/**
 * The role-aware dashboard.
 *
 * <p>ADMIN gets the channel-aware overview ({@link AdminChannelOverviewComponent}:
 * Portal / Shopify / All). Every other operational role gets its role-shaped
 * card set built from {@code GET /api/dashboard/summary} (salesperson and team
 * lead order stages + My Day, packer queues, accountant money).
 */
@Component({
  selector: 'admin-dashboard',
  imports: [
    AdminChannelOverviewComponent,
    DeliveryPartnerOverviewComponent,
    TeamsOverviewWidgetComponent,
    CountUpDirective,
    PageHeaderComponent,
    RouterLink,
  ],
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit, OnDestroy {
  private readonly service = inject(DashboardService);
  private readonly myDayService = inject(MyDayService);
  private readonly teamPerformanceService = inject(TeamPerformanceService);
  private readonly router = inject(Router);
  private readonly events = inject(AdminEventsService);
  protected readonly auth = inject(AuthService);

  /** The signed-in user's role, driving which card set the dashboard renders (Req 3.1). */
  protected readonly role = computed<Role | null>(() => this.auth.session()?.role ?? null);

  /** Whether the current user is an ADMIN (gates the channel overview + SSE). */
  protected readonly isAdmin = computed(() => this.role() === Role.ADMIN);

  /**
   * Whether the current user is a TEAM_LEAD. The backend returns their
   * team-aggregated order summary in the same shape as a salesperson's, so the
   * salesperson section renders it; this flag hides the salesperson-only actions
   * (New Order / My Leads) a team lead can't perform and relabels the section.
   */
  protected readonly isTeamLead = computed(() => this.role() === Role.TEAM_LEAD);

  /** A true salesperson (not a team lead) — gets the "My Day" + win-back widgets. */
  protected readonly isSalesperson = computed(() => this.role() === Role.SALESPERSON);

  /** Owner one-screen snapshot (ADMIN) — today's trading + the actionable backlog (ENHANCEMENT 1.2). */
  protected readonly ownerSnapshot = signal<OwnerSnapshot | null>(null);

  /** Per-channel revenue + margin, this month (ADMIN, ENHANCEMENT 3.6). */
  protected readonly channelMargin = signal<ChannelMarginReport | null>(null);

  // --- My Day + Win-back + Reorder-due (salesperson self-service) ---------
  protected readonly myDay = signal<MyDay | null>(null);
  protected readonly winBack = signal<WinBackCustomer[]>([]);
  protected readonly reorderDue = signal<ReorderDueCustomer[]>([]);
  protected readonly teamPerformance = signal<TeamPerformance | null>(null);

  /** Loads the salesperson's My Day snapshot + win-back + reorder-due (non-fatal). */
  private loadMyDay(): void {
    this.myDayService.myDay().subscribe({
      next: (d) => this.myDay.set(d),
      error: () => this.myDay.set(null),
    });
    this.myDayService.winBack().subscribe({
      next: (rows) => this.winBack.set(rows.slice(0, 6)),
      error: () => this.winBack.set([]),
    });
    this.myDayService.reorderDue().subscribe({
      next: (rows) => this.reorderDue.set(rows.slice(0, 6)),
      error: () => this.reorderDue.set([]),
    });
  }

  /** Loads the Team Lead's server-scoped direct-report KPI snapshot. */
  private loadTeamPerformance(): void {
    this.teamPerformanceService.performance().subscribe({
      next: (performance) => this.teamPerformance.set(performance),
      error: () => this.teamPerformance.set(null),
    });
  }

  /** Opens WhatsApp for a reorder-due customer with a friendly nudge. */
  waReorder(c: ReorderDueCustomer): void {
    openWhatsApp(c.mobile, whatsAppMessage('followup', { customerName: c.customerName }));
  }

  /** ₹ formatter for the My Day / win-back figures (no decimals for compactness). */
  money(value: string | number | null | undefined): string {
    const n = Number(value ?? 0);
    return '₹' + (Number.isFinite(n) ? Math.round(n).toLocaleString('en-IN') : '0');
  }

  /** Opens WhatsApp for a lapsed customer with a friendly win-back follow-up. */
  waCustomer(c: WinBackCustomer): void {
    openWhatsApp(c.mobile, whatsAppMessage('followup', { customerName: c.customerName }));
  }

  /**
   * The salesperson "My tasks" inbox: reorder-due customers (most overdue first,
   * highest priority) merged with win-back customers (longest-lapsed first),
   * into one ranked actionable list. Purely a frontend projection of the already
   * loaded {@code reorderDue()} + {@code winBack()} signals — no new backend call.
   */
  protected readonly taskInbox = computed<TaskInboxItem[]>(() => {
    const items: TaskInboxItem[] = [];
    for (const c of this.reorderDue()) {
      const overdue = Number(c.overdueDays ?? 0);
      const reason =
        overdue > 0
          ? `${overdue}d overdue for reorder`
          : overdue < 0
            ? `due to reorder in ${Math.abs(overdue)}d`
            : 'due to reorder today';
      items.push({
        kind: 'reorder',
        mobile: c.mobile,
        customerName: c.customerName,
        reason,
        // Reorder-due always outranks win-back; overdue days widen the gap.
        priority: 1_000_000 + Math.max(overdue, -30) * 1000 + Number(c.totalValue ?? 0) / 1000,
        icon: 'ti ti-rotate-clockwise',
        accent: overdue > 0 ? '#d63939' : '#f59f00',
        reorderSource: c,
      });
    }
    for (const c of this.winBack()) {
      items.push({
        kind: 'winback',
        mobile: c.mobile,
        customerName: c.customerName,
        reason: `no order in ${Number(c.daysSinceLastOrder ?? 0)}d`,
        priority: Number(c.daysSinceLastOrder ?? 0) * 10 + Number(c.totalValue ?? 0) / 1000,
        icon: 'ti ti-user-heart',
        accent: '#206bc4',
        winbackSource: c,
      });
    }
    return items.sort((a, b) => b.priority - a.priority).slice(0, 8);
  });

  /** Opens WhatsApp for a "My tasks" row, routing to the right source shape. */
  waTask(item: TaskInboxItem): void {
    if (item.reorderSource) {
      this.waReorder(item.reorderSource);
    } else if (item.winbackSource) {
      this.waCustomer(item.winbackSource);
    }
  }

  // --- Role-shaped summary (all roles, Req 3.1–3.6) -----------------------
  protected readonly summary = signal<RoleDashboardSummary | null>(null);
  protected readonly summaryLoading = signal(true);
  protected readonly summaryError = signal<string | null>(null);

  /**
   * The salesperson/team-lead orders folded into the business-facing lifecycle
   * stage groups (same partition the Orders page uses). Only non-empty groups are
   * shown, in lifecycle order.
   */
  protected readonly salespersonStageGroups = computed<StageGroupCount[]>(() => {
    const map = this.summary()?.salesperson?.ordersByStatus;
    if (!map) {
      return [];
    }
    const result: StageGroupCount[] = [];
    for (const group of ORDER_STATUS_GROUPS) {
      let value = 0;
      for (const status of group.statuses) {
        value += map[String(status)] ?? 0;
      }
      if (value <= 0) {
        continue;
      }
      const style = STAGE_GROUP_STYLE[group.key] ?? DEFAULT_STAGE_STYLE;
      result.push({
        key: group.key,
        label: group.label,
        value,
        accent: style.accent,
        accentSoft: style.accentSoft,
        icon: style.icon,
      });
    }
    return result;
  });

  /** Total orders in scope (sum across all stage groups) for the header count. */
  protected readonly salespersonOrderTotal = computed<number>(() =>
    this.salespersonStageGroups().reduce((sum, g) => sum + g.value, 0),
  );

  /**
   * The salesperson's lead pipeline-by-stage counts (NEW/CONTACTED/QUOTED) as
   * ordered, labelled counts for the "My leads" widget (Req 6.6, lead-management).
   */
  protected readonly salespersonLeadStages = computed<StatusCount[]>(() => {
    const map = this.summary()?.salesperson?.leadPipeline;
    if (!map) {
      return [];
    }
    const order = ['NEW', 'CONTACTED', 'QUOTED'];
    return order
      .filter((k) => k in map)
      .map((key) => ({ key, label: this.humanizeStatus(key), value: map[key] ?? 0 }));
  });

  /** The salesperson's due-follow-up count for the widget badge. */
  protected readonly dueFollowUps = computed(() => this.summary()?.salesperson?.dueFollowUps ?? 0);

  /** Total orders sitting in an exception/terminal-review state (admin triage strip). */
  protected readonly adminExceptionTotal = computed<number>(() => {
    const map = this.summary()?.admin?.exceptionStates;
    if (!map) {
      return 0;
    }
    return Object.values(map).reduce((sum, n) => sum + (n ?? 0), 0);
  });

  /** Time-of-day greeting for the welcome hero card. */
  protected readonly greeting = computed(() => {
    const hr = new Date().getHours();
    if (hr < 12) return 'Good morning';
    if (hr < 17) return 'Good afternoon';
    return 'Good evening';
  });

  ngOnInit(): void {
    // Payment Verifier has no dashboard content (their work lives on the payment
    // queue) — send them straight to their home.
    if (this.role() === Role.PAYMENT_VERIFIER) {
      void this.router.navigate(['/payments']);
      return;
    }
    // A CA works from the dedicated GST/accounting dashboard.
    if (this.role() === Role.CA) {
      void this.router.navigate(['/ca/gst']);
      return;
    }
    // A packer works from the Packing queue — send them straight there instead
    // of the thin dashboard summary (consistent with CA / Payment Verifier).
    if (this.role() === Role.PACKING_USER) {
      void this.router.navigate(['/packing']);
      return;
    }
    // The role-shaped summary is available to every operational role (Req 3.1);
    // for an admin it also supplies the insights counts.
    this.loadSummary();
    if (this.isAdmin()) {
      // Open the real-time stream for the Activity + Notifications panels; the
      // service is idempotent and no-ops when unauthenticated.
      this.events.connect();
    }
  }

  ngOnDestroy(): void {
    this.events.disconnect();
  }

  /** Loads the role-shaped dashboard summary for the current user (Req 3.1–3.6). */
  loadSummary(): void {
    this.summaryLoading.set(true);
    this.summaryError.set(null);
    this.service.roleSummary().subscribe({
      next: (s) => {
        this.summary.set(s);
        this.summaryLoading.set(false);
      },
      error: () => {
        this.summaryError.set('Could not load your dashboard. Please try again.');
        this.summaryLoading.set(false);
      },
    });
    if (this.isSalesperson()) {
      this.loadMyDay();
    }
    if (this.isTeamLead()) {
      this.loadTeamPerformance();
    }
    if (this.isAdmin()) {
      this.loadOwnerSnapshot();
    }
  }

  /** Loads the owner one-screen snapshot + reorder suggestions (ADMIN only, non-fatal). */
  private loadOwnerSnapshot(): void {
    this.service.ownerSnapshot().subscribe({
      next: (s) => this.ownerSnapshot.set(s),
      error: () => this.ownerSnapshot.set(null),
    });
    // Per-channel margin for the current calendar month (ENHANCEMENT 3.6).
    const now = new Date();
    const from = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-01`;
    this.service.channelMargin(from).subscribe({
      next: (r) => this.channelMargin.set(r),
      error: () => this.channelMargin.set(null),
    });
  }

  /**
   * Humanises a backend status name using the single shared label map
   * (`shared/status-badge.component`) so the dashboard shows the same business
   * vocabulary as every other screen (e.g. "Tracking ID Assigned", "In Transit").
   */
  humanizeStatus(status: string): string {
    return humanizeStatus(status);
  }

  /** Formats a number as Indian Rupees with two decimals. */
  inr(value: number | null | undefined): string {
    const n = typeof value === 'number' ? value : 0;
    return `₹${n.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  }
}
