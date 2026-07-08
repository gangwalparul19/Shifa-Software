import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { LeadsService } from './leads.service';
import {
  LEAD_STATUS_LABELS,
  LeadStatus,
  LeadSummary,
  leadPillClass,
} from './leads.model';
import { LEAD_SOURCE_OPTIONS, LeadSource } from '../orders/orders.model';

/**
 * The acting user's due follow-ups (design §Frontend, Req 5.2): the non-terminal
 * leads whose follow-up date is on/before today, from
 * {@code GET /api/leads/follow-ups/due}. Overdue leads (date before today) are
 * flagged so the salesperson can triage them first. Tapping a lead jumps to the
 * pipeline where the detail drawer + actions live.
 */
@Component({
  selector: 'admin-due-follow-ups',
  imports: [RouterLink, DatePipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './due-follow-ups.component.html',
  styleUrl: './due-follow-ups.component.css',
})
export class DueFollowUpsComponent implements OnInit {
  private readonly service = inject(LeadsService);

  protected readonly statusLabels = LEAD_STATUS_LABELS;
  protected readonly pillClass = leadPillClass;

  protected readonly leads = signal<LeadSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  /** Today's date as yyyy-MM-dd, for the overdue comparison. */
  private readonly today = new Date().toISOString().slice(0, 10);

  protected readonly overdueCount = computed(
    () => this.leads().filter((l) => this.isOverdue(l)).length,
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.dueFollowUps().subscribe({
      next: (rows) => {
        this.leads.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load your follow-ups. Please try again.');
        this.loading.set(false);
      },
    });
  }

  isOverdue(lead: LeadSummary): boolean {
    return !!lead.followUpDate && lead.followUpDate < this.today;
  }

  sourceLabel(source: LeadSource): string {
    return LEAD_SOURCE_OPTIONS.find((o) => o.value === source)?.label ?? source;
  }

  statusOf(status: LeadStatus): string {
    return this.statusLabels[status];
  }
}
