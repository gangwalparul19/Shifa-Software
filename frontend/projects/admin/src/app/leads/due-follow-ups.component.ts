import { IstDatePipe } from '../shared/ist-date.pipe';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { LeadsService } from './leads.service';
import {
  LEAD_STATUS_LABELS,
  LeadStatus,
  LeadSummary,
  leadPillClass,
} from './leads.model';
import { LEAD_SOURCE_OPTIONS, LeadSource } from '../orders/orders.model';
import { ToastService } from '../shared/toast.service';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';

/**
 * The acting user's due follow-ups (design §Frontend, Req 5.2): the non-terminal
 * leads whose follow-up date is on/before today, from
 * {@code GET /api/leads/follow-ups/due}. Overdue leads (date before today) are
 * flagged so the salesperson can triage them first. Tapping a lead jumps to the
 * pipeline where the detail drawer + actions live.
 */
@Component({
  selector: 'admin-due-follow-ups',
  imports: [RouterLink, IstDatePipe, PageHeaderComponent, PaginationComponent, StatePanelComponent],
  templateUrl: './due-follow-ups.component.html',
  styleUrl: './due-follow-ups.component.css',
})
export class DueFollowUpsComponent implements OnInit {
  private readonly service = inject(LeadsService);
  private readonly toasts = inject(ToastService);

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

  // --- Client-side paging -------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('dueFollowUps', 10));
  protected readonly totalElements = computed(() => this.leads().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed(() => {
    const s = this.page() * this.size();
    return this.leads().slice(s, s + this.size());
  });

  ngOnInit(): void {
    this.load();
  }

  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('dueFollowUps', s);
    this.page.set(0);
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.dueFollowUps().subscribe({
      next: (rows) => {
        this.leads.set(rows);
        this.page.set(0);
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

  /** A Tabler brand/context icon for a lead source (mirrors the Leads list). */
  sourceIcon(source: LeadSource | string | null | undefined): string {
    switch (source) {
      case 'WHATSAPP':
        return 'ti-brand-whatsapp';
      case 'INSTAGRAM':
        return 'ti-brand-instagram';
      case 'FACEBOOK':
        return 'ti-brand-facebook';
      case 'GOOGLE':
        return 'ti-brand-google';
      case 'OFFLINE':
        return 'ti-building-store';
      case 'OTHER':
        return 'ti-dots-circle-horizontal';
      default:
        return 'ti-broadcast';
    }
  }

  /** Direct action from the task list; keeps the lead card open. */
  callLead(lead: LeadSummary, event: Event): void {
    event.stopPropagation();
    window.location.href = `tel:${lead.customerMobile ?? ''}`;
  }

  /** Direct follow-up message from the task list. */
  messageLead(lead: LeadSummary, event: Event): void {
    event.stopPropagation();
    if (!lead.customerMobile) {
      this.toasts.error('This lead has no mobile number.');
      return;
    }
    const ok = openWhatsApp(
      lead.customerMobile,
      whatsAppMessage('followup', { customerName: lead.customerName }),
    );
    if (!ok) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }

  statusOf(status: LeadStatus): string {
    return this.statusLabels[status];
  }
}
