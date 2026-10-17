import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { CrmOverviewService } from './crm-overview.service';
import { CrmOverview } from './crm-overview.model';
import { LEAD_STAGE_ORDER, LEAD_STATUS_LABELS, LeadStatus } from '../leads/leads.model';

/**
 * The unified CRM cockpit (CRM expansion Phase 2, Task 15; Req 14). ADMIN-only.
 * One call (`GET /api/admin/crm/overview`) powers the whole view: lead pipeline,
 * follow-up health, monthly targets, and the performance leaderboard. Each
 * section links to its detailed view (Req 14.2). No computation here — pure
 * composition of the existing read models (Req 14.3).
 */
@Component({
  selector: 'admin-crm-dashboard',
  imports: [RouterLink, CurrencyPipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './crm-dashboard.component.html',
  styleUrl: './crm-dashboard.component.css',
})
export class CrmDashboardComponent implements OnInit {
  private readonly service = inject(CrmOverviewService);

  protected readonly stageOrder = LEAD_STAGE_ORDER;
  protected readonly stageLabels = LEAD_STATUS_LABELS;

  protected readonly overview = signal<CrmOverview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  protected readonly pipelineTotal = computed(() => {
    const p = this.overview()?.pipeline;
    if (!p) {
      return 0;
    }
    return this.stageOrder.reduce((sum, s) => sum + (p[s] ?? 0), 0);
  });

  protected readonly topTargets = computed(() => (this.overview()?.targets ?? []).slice(0, 5));
  protected readonly topPerformers = computed(() => (this.overview()?.leaderboard ?? []).slice(0, 5));

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
  }

  stageCount(stage: LeadStatus): number {
    return this.overview()?.pipeline?.[stage] ?? 0;
  }
}
