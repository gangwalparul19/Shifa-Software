import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { AuthService, Role } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { PeriodPickerComponent } from '../shared/period-picker.component';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { nowIstLocalMinute } from '../shared/business-date.util';
import {
  PeriodSelection,
  THIS_MONTH,
  isUsableSelection,
  periodDates,
  periodLabel,
} from '../shared/period-selection';
import { leadSourceLabel } from '../orders/order-presentation';
import { CrmOverviewService } from './crm-overview.service';
import { CrmOverview } from './crm-overview.model';
import { LeadsService } from '../leads/leads.service';
import { TasksService } from '../tasks/tasks.service';
import { MeetingsService } from '../meetings/meetings.service';
import {
  ConversionReport,
  GroupPerformanceReport,
  LEAD_STAGE_ORDER,
  LEAD_STATUS_LABELS,
  LOST_REASON_OPTIONS,
  LeadStatus,
  LeadSummary,
  LostReasonReport,
  leadPillClass,
} from '../leads/leads.model';

/** A follow-up the lead cockpit flags, with why it is flagged. */
interface AttentionRow {
  lead: LeadSummary;
  kind: 'MISSED' | 'DUE';
}

/** Most flagged follow-ups listed on the cockpit; the full list is one click away. */
const ATTENTION_LIMIT = 8;

/**
 * The unified CRM cockpit (CRM expansion Phase 2, Task 15; Req 14). Open to ADMIN and to
 * the view-only CRM Lead, for whom it is the home page. Everything on it is read-only and
 * each section links to its detailed screen (Req 14.2).
 *
 * <p>One call (`GET /api/admin/crm/overview`) supplies the pipeline, follow-up health,
 * targets and leaderboard. The conversion, lost-reason and per-salesperson panels follow
 * the chosen period; the open tasks and scheduled meetings counts are "right now". Each
 * extra panel loads on its own and fails on its own, so one slow or failing call never
 * blanks the page. No figures are computed here beyond simple sums of what the server sent.
 */
@Component({
  selector: 'admin-crm-dashboard',
  imports: [
    RouterLink,
    CurrencyPipe,
    IstDatePipe,
    PageHeaderComponent,
    StatePanelComponent,
    PeriodPickerComponent,
  ],
  templateUrl: './crm-dashboard.component.html',
  styleUrl: './crm-dashboard.component.css',
})
export class CrmDashboardComponent implements OnInit {
  private readonly service = inject(CrmOverviewService);
  private readonly leadsService = inject(LeadsService);
  private readonly tasksService = inject(TasksService);
  private readonly meetingsService = inject(MeetingsService);
  private readonly auth = inject(AuthService);

  protected readonly stageOrder = LEAD_STAGE_ORDER;
  protected readonly stageLabels = LEAD_STATUS_LABELS;
  protected readonly sourceLabel = leadSourceLabel;

  /** Whether the viewer can only look (the CRM Lead); shown in the page subtitle. */
  protected readonly viewOnly = computed(() => this.auth.hasAnyRole(Role.CRM_LEAD));

  /** The Analytics page (targets live there) is admin-only; a CRM Lead is sent to team sales. */
  protected readonly targetsLink = computed(() =>
    this.auth.hasAnyRole(Role.ADMIN) ? '/analytics' : '/teams-overview',
  );

  protected readonly overview = signal<CrmOverview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Period panels: conversion by source / by salesperson, lost reasons ------------------
  protected readonly period = signal<PeriodSelection>(THIS_MONTH);
  protected readonly periodText = computed(() => periodLabel(this.period()));
  protected readonly conversion = signal<ConversionReport | null>(null);
  protected readonly byUser = signal<GroupPerformanceReport | null>(null);
  protected readonly lostReasons = signal<LostReasonReport | null>(null);
  protected readonly periodLoading = signal(false);
  protected readonly periodError = signal<string | null>(null);
  /** Numbers each period request so a slow older reply cannot overwrite a newer one. */
  private periodRequest = 0;

  // --- "Right now" counts ----------------------------------------------------------------
  protected readonly openTasks = signal<number | null>(null);
  protected readonly overdueTasks = signal<number | null>(null);
  protected readonly scheduledMeetings = signal<number | null>(null);

  protected readonly pipelineTotal = computed(() => {
    const p = this.overview()?.pipeline;
    if (!p) {
      return 0;
    }
    return this.stageOrder.reduce((sum, s) => sum + (p[s] ?? 0), 0);
  });

  protected readonly topTargets = computed(() => (this.overview()?.targets ?? []).slice(0, 5));
  protected readonly topPerformers = computed(() => (this.overview()?.leaderboard ?? []).slice(0, 5));

  /** Missed follow-ups first, then the ones due now; capped so the card stays short. */
  protected readonly attention = computed<AttentionRow[]>(() => {
    const health = this.overview()?.followUpHealth;
    if (!health) {
      return [];
    }
    const rows: AttentionRow[] = [
      ...health.missed.map((lead) => ({ lead, kind: 'MISSED' as const })),
      ...health.due.map((lead) => ({ lead, kind: 'DUE' as const })),
    ];
    return rows.slice(0, ATTENTION_LIMIT);
  });

  /** Leads captured, leads won and the win rate across every source in the chosen period. */
  protected readonly periodTotals = computed(() => {
    const rows = this.conversion()?.bySource ?? [];
    const leads = rows.reduce((sum, r) => sum + r.leads, 0);
    const won = rows.reduce((sum, r) => sum + r.won, 0);
    return { leads, won, rate: leads > 0 ? Math.round((won / leads) * 1000) / 10 : null };
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.overview().subscribe({
      next: (data) => {
        this.overview.set(data);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load the CRM overview. Please try again.');
        this.loading.set(false);
      },
    });
    this.loadPeriodPanels();
    this.loadCounts();
  }

  onPeriodChanged(selection: PeriodSelection): void {
    this.period.set(selection);
    this.loadPeriodPanels();
  }

  /** The period panels: three lead reports over the chosen days (all time for "Overall"). */
  private loadPeriodPanels(): void {
    const selection = this.period();
    if (!isUsableSelection(selection)) {
      return;
    }
    const dates = periodDates(selection);
    const from = dates?.from ?? null;
    const to = dates?.to ?? null;
    const request = ++this.periodRequest;
    this.periodLoading.set(true);
    this.periodError.set(null);
    forkJoin({
      conversion: this.leadsService.reportConversion(from, to),
      byUser: this.leadsService.reportByUser(from, to),
      lost: this.leadsService.reportLostReasons(from, to),
    }).subscribe({
      next: (result) => {
        if (request !== this.periodRequest) {
          return;
        }
        this.conversion.set(result.conversion);
        this.byUser.set(result.byUser);
        this.lostReasons.set(result.lost);
        this.periodLoading.set(false);
      },
      error: () => {
        if (request !== this.periodRequest) {
          return;
        }
        this.periodError.set('Could not load the lead performance figures for this period.');
        this.periodLoading.set(false);
      },
    });
  }

  /** Open tasks (and how many are overdue) and scheduled meetings; each is non-fatal. */
  private loadCounts(): void {
    const now = nowIstLocalMinute();
    this.tasksService.list('OPEN').subscribe({
      next: (tasks) => {
        this.openTasks.set(tasks.length);
        this.overdueTasks.set(tasks.filter((t) => !!t.dueAt && t.dueAt < now).length);
      },
      error: () => {
        this.openTasks.set(null);
        this.overdueTasks.set(null);
      },
    });
    this.meetingsService.list('SCHEDULED').subscribe({
      next: (meetings) => this.scheduledMeetings.set(meetings.length),
      error: () => this.scheduledMeetings.set(null),
    });
  }

  stageCount(stage: LeadStatus): number {
    return this.overview()?.pipeline?.[stage] ?? 0;
  }

  /** The shared badge colour for a lead stage (green won, red lost, blue new, amber in progress). */
  statusTone(status: LeadStatus): string {
    switch (leadPillClass(status)) {
      case 'is-green':
        return 'tone-green';
      case 'is-red':
        return 'tone-red';
      case 'is-blue':
        return 'tone-blue';
      default:
        return 'tone-amber';
    }
  }

  /** The readable name of a lost reason (e.g. OUT_OF_STOCK becomes "Out of stock"). */
  lostReasonLabel(reason: string): string {
    return (
      LOST_REASON_OPTIONS.find((o) => o.value === reason)?.label ??
      reason.toLowerCase().replace(/_/g, ' ')
    );
  }

  /** Share of the period's lost leads that a reason accounts for, for the bar width. */
  lostShare(count: number): number {
    const total = this.lostReasons()?.total ?? 0;
    return total > 0 ? Math.round((count / total) * 100) : 0;
  }
}
