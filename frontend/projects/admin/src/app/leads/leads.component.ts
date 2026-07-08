import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { ApiError, AuthService, Role } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { LEAD_SOURCE_OPTIONS, LeadSource } from '../orders/orders.model';
import { LeadsService } from './leads.service';
import {
  CreateLeadRequest,
  LEAD_STAGE_ORDER,
  LEAD_STATUS_LABELS,
  LeadDetail,
  LeadStatus,
  LeadSummary,
  LOST_REASON_OPTIONS,
  LostReason,
  leadPillClass,
} from './leads.model';

/** The status-filter lens options for the pipeline list (Req 3.3, 3.4). */
type StatusLens = LeadStatus | 'ALL';

/**
 * Leads / sales-pipeline screen (design §Frontend, Req 8.1, 8.2). Mobile-first:
 *
 * <ul>
 *   <li>a scrollable status filter-tab lens (All / New / Contacted / Quoted /
 *       Won / Lost) carrying per-stage counts, mirroring the Orders list;</li>
 *   <li>tappable lead cards with a colored status pill, opening a detail
 *       drawer that mirrors the order-detail drawer;</li>
 *   <li>a capture form (name required, source + OTHER note, optional
 *       mobile/email/note/follow-up) reachable from a top action + FAB;</li>
 *   <li>in the drawer: advance-status (NEW→CONTACTED→QUOTED), Mark Lost with a
 *       reason picker, set/clear follow-up, and Convert (→ New Order pre-filled
 *       from the lead, which posts the convert endpoint on save).</li>
 * </ul>
 *
 * Salesperson scoping, transition legality, and the LOST-reason requirement are
 * all enforced server-side; the client surfaces errors as toasts.
 */
@Component({
  selector: 'admin-leads',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    DatePipe,
    PageHeaderComponent,
    StatePanelComponent,
  ],
  templateUrl: './leads.component.html',
  styleUrl: './leads.component.css',
})
export class LeadsComponent implements OnInit, OnDestroy {
  private readonly service = inject(LeadsService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  // --- Constants exposed to the template ---------------------------------
  protected readonly stageOrder = LEAD_STAGE_ORDER;
  protected readonly statusLabels = LEAD_STATUS_LABELS;
  protected readonly leadSourceOptions = LEAD_SOURCE_OPTIONS;
  protected readonly lostReasonOptions = LOST_REASON_OPTIONS;
  protected readonly pillClass = leadPillClass;

  /** The status filter tabs shown above the list, in display order. */
  protected readonly statusTabs: { key: StatusLens; label: string }[] = [
    { key: 'ALL', label: 'All' },
    ...LEAD_STAGE_ORDER.map((s) => ({ key: s as StatusLens, label: LEAD_STATUS_LABELS[s] })),
  ];

  // --- List state ---------------------------------------------------------
  protected readonly leads = signal<LeadSummary[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly statusLens = signal<StatusLens>('ALL');

  /** Active-stage counts (NEW/CONTACTED/QUOTED) from the pipeline endpoint. */
  protected readonly pipeline = signal<Record<LeadStatus, number>>({
    NEW: 0,
    CONTACTED: 0,
    QUOTED: 0,
    WON: 0,
    LOST: 0,
  });

  protected readonly search = new FormControl<string>('', { nonNullable: true });
  protected readonly sourceFilter = new FormControl<string>('', { nonNullable: true });

  /** The loaded leads filtered by the active status lens. */
  protected readonly visibleLeads = computed<LeadSummary[]>(() => {
    const lens = this.statusLens();
    const rows = this.leads();
    if (lens === 'ALL') {
      return rows;
    }
    return rows.filter((l) => l.status === lens);
  });

  /** Per-stage counts derived from the loaded leads (drives the tab badges). */
  protected readonly stageCounts = computed<Record<StatusLens, number>>(() => {
    const counts: Record<StatusLens, number> = {
      ALL: 0,
      NEW: 0,
      CONTACTED: 0,
      QUOTED: 0,
      WON: 0,
      LOST: 0,
    };
    for (const lead of this.leads()) {
      counts.ALL += 1;
      counts[lead.status] += 1;
    }
    return counts;
  });

  /** Whether any list filter/search is active (drives the empty-state copy). */
  protected readonly hasFilters = computed(
    () => !!this.search.value.trim() || !!this.sourceFilter.value,
  );

  // --- Capture / edit form ------------------------------------------------
  protected readonly formOpen = signal(false);
  protected readonly formBusy = signal(false);
  protected readonly formError = signal<string | null>(null);
  /** When set, the form edits this lead; otherwise it captures a new one. */
  protected readonly editingId = signal<number | null>(null);

  protected readonly leadForm = new FormGroup({
    customerName: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(120)],
    }),
    leadSource: new FormControl<'' | LeadSource>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    leadSourceNote: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(200)],
    }),
    customerMobile: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.pattern(/^\d{10}$/)],
    }),
    customerEmail: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.email, Validators.maxLength(150)],
    }),
    note: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(1000)],
    }),
    followUpDate: new FormControl<string>('', { nonNullable: true }),
  });

  /** Whether the OTHER free-text source note field is shown (Req 1.2). */
  protected readonly showSourceNote = signal(false);

  // --- Detail drawer ------------------------------------------------------
  protected readonly selectedDetail = signal<LeadDetail | null>(null);
  protected readonly detailLoading = signal(false);
  protected readonly detailError = signal<string | null>(null);
  protected readonly detailBusy = signal(false);

  // --- Mark-lost modal ----------------------------------------------------
  protected readonly lostOpen = signal(false);
  protected readonly lostForm = new FormGroup({
    lostReason: new FormControl<'' | LostReason>('', {
      nonNullable: true,
      validators: [Validators.required],
    }),
    lostReasonNote: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(200)],
    }),
  });

  // --- Follow-up inline editor (in the drawer) ---------------------------
  protected readonly followUpControl = new FormControl<string>('', { nonNullable: true });

  private readonly destroy$ = new Subject<void>();

  /** Whether the current user may capture/convert leads (SALESPERSON + ADMIN). */
  protected readonly canManage = computed(() =>
    this.auth.hasAnyRole(Role.SALESPERSON, Role.ADMIN),
  );

  ngOnInit(): void {
    this.load();
    this.loadPipeline();
    this.showSourceNote.set(false);
    this.leadForm.controls.leadSource.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe((v) => this.showSourceNote.set(v === 'OTHER'));

    this.search.valueChanges
      .pipe(debounceTime(300), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe(() => this.load());
    this.sourceFilter.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.load());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // --- Loading ------------------------------------------------------------

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service
      .list({
        q: this.search.value,
        source: (this.sourceFilter.value || null) as LeadSource | null,
      })
      .subscribe({
        next: (rows) => {
          this.leads.set(rows);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load leads. Please try again.');
          this.loading.set(false);
        },
      });
  }

  loadPipeline(): void {
    this.service.pipeline().subscribe({
      next: (counts) => this.pipeline.set({ ...this.pipeline(), ...counts }),
      error: () => {
        /* the tab counts fall back to the loaded-list derivation */
      },
    });
  }

  setLens(lens: StatusLens): void {
    this.statusLens.set(lens);
  }

  clearSearch(): void {
    this.search.setValue('');
  }

  // --- Capture / edit -----------------------------------------------------

  openCapture(): void {
    this.editingId.set(null);
    this.formError.set(null);
    this.leadForm.reset({
      customerName: '',
      leadSource: '',
      leadSourceNote: '',
      customerMobile: '',
      customerEmail: '',
      note: '',
      followUpDate: '',
    });
    this.showSourceNote.set(false);
    this.formOpen.set(true);
  }

  /** Opens the form pre-filled to edit the given lead's capture fields (Req 3.6). */
  openEdit(lead: LeadDetail): void {
    this.editingId.set(lead.id);
    this.formError.set(null);
    this.leadForm.reset({
      customerName: lead.customerName,
      leadSource: lead.leadSource,
      leadSourceNote: lead.leadSourceNote ?? '',
      customerMobile: lead.customerMobile ?? '',
      customerEmail: lead.customerEmail ?? '',
      note: lead.note ?? '',
      followUpDate: lead.followUpDate ?? '',
    });
    this.showSourceNote.set(lead.leadSource === 'OTHER');
    this.formOpen.set(true);
  }

  closeForm(): void {
    this.formOpen.set(false);
    this.formError.set(null);
  }

  submitForm(): void {
    if (this.formBusy()) {
      return;
    }
    if (this.leadForm.invalid) {
      this.leadForm.markAllAsTouched();
      return;
    }
    const raw = this.leadForm.getRawValue();
    const isOther = raw.leadSource === 'OTHER';
    const note = raw.leadSourceNote.trim();
    const email = raw.customerEmail.trim();
    const mobile = raw.customerMobile.trim();
    const workingNote = raw.note.trim();
    const payload: CreateLeadRequest = {
      customerName: raw.customerName.trim(),
      leadSource: raw.leadSource as LeadSource,
      ...(isOther && note ? { leadSourceNote: note } : {}),
      ...(mobile ? { customerMobile: mobile } : {}),
      ...(email ? { customerEmail: email } : {}),
      ...(workingNote ? { note: workingNote } : {}),
      ...(raw.followUpDate ? { followUpDate: raw.followUpDate } : { followUpDate: null }),
    };

    this.formBusy.set(true);
    this.formError.set(null);
    const editing = this.editingId();
    const call = editing
      ? this.service.edit(editing, payload)
      : this.service.capture(payload);
    call.subscribe({
      next: (lead) => {
        this.formBusy.set(false);
        this.formOpen.set(false);
        this.toasts.success(
          editing ? 'Lead updated.' : `Lead captured for ${lead.customerName}.`,
        );
        this.load();
        this.loadPipeline();
        // If we edited the open drawer's lead, refresh it in place.
        if (editing && this.selectedDetail()?.id === editing) {
          this.selectedDetail.set(lead);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.formBusy.set(false);
        this.formError.set(this.messageOf(err) ?? 'Could not save the lead. Please try again.');
      },
    });
  }

  // --- Detail drawer ------------------------------------------------------

  openDetail(lead: LeadSummary): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.selectedDetail.set(null);
    this.service.detail(lead.id).subscribe({
      next: (detail) => {
        this.selectedDetail.set(detail);
        this.followUpControl.setValue(detail.followUpDate ?? '');
        this.detailLoading.set(false);
      },
      error: () => {
        this.detailError.set('Could not load this lead.');
        this.detailLoading.set(false);
      },
    });
  }

  private refreshDetail(id: number): void {
    this.service.detail(id).subscribe((d) => {
      this.selectedDetail.set(d);
      this.followUpControl.setValue(d.followUpDate ?? '');
    });
  }

  closeDetail(): void {
    this.selectedDetail.set(null);
    this.detailError.set(null);
    this.lostOpen.set(false);
  }

  // --- Advance status -----------------------------------------------------

  /** Whether the open lead is still active (non-terminal). */
  isActive(lead: LeadDetail | null): boolean {
    return !!lead && lead.status !== 'WON' && lead.status !== 'LOST';
  }

  /** The next forward stage for a lead (NEW→CONTACTED→QUOTED), or null at QUOTED/terminal. */
  nextStage(status: LeadStatus): LeadStatus | null {
    if (status === 'NEW') {
      return 'CONTACTED';
    }
    if (status === 'CONTACTED') {
      return 'QUOTED';
    }
    return null;
  }

  /** Advances the lead one step through the funnel (Req 2.2). */
  advance(lead: LeadDetail): void {
    const target = this.nextStage(lead.status);
    if (!target || this.detailBusy()) {
      return;
    }
    this.detailBusy.set(true);
    this.service.changeStatus(lead.id, { toStatus: target }).subscribe({
      next: (updated) => {
        this.detailBusy.set(false);
        this.selectedDetail.set(updated);
        this.toasts.success(`Lead moved to ${this.statusLabels[target]}.`);
        this.load();
        this.loadPipeline();
      },
      error: (err: HttpErrorResponse) => {
        this.detailBusy.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not update the lead status.');
      },
    });
  }

  // --- Mark lost ----------------------------------------------------------

  openMarkLost(): void {
    this.lostForm.reset({ lostReason: '', lostReasonNote: '' });
    this.lostOpen.set(true);
  }

  closeMarkLost(): void {
    this.lostOpen.set(false);
  }

  submitMarkLost(): void {
    const lead = this.selectedDetail();
    if (!lead || this.detailBusy()) {
      return;
    }
    if (this.lostForm.invalid) {
      this.lostForm.markAllAsTouched();
      return;
    }
    const raw = this.lostForm.getRawValue();
    const note = raw.lostReasonNote.trim();
    this.detailBusy.set(true);
    this.service
      .changeStatus(lead.id, {
        toStatus: 'LOST',
        lostReason: raw.lostReason as LostReason,
        ...(note ? { lostReasonNote: note } : {}),
      })
      .subscribe({
        next: (updated) => {
          this.detailBusy.set(false);
          this.lostOpen.set(false);
          this.selectedDetail.set(updated);
          this.toasts.info('Lead marked lost.');
          this.load();
          this.loadPipeline();
        },
        error: (err: HttpErrorResponse) => {
          this.detailBusy.set(false);
          this.toasts.error(this.messageOf(err) ?? 'Could not mark the lead lost.');
        },
      });
  }

  // --- Follow-up ----------------------------------------------------------

  saveFollowUp(): void {
    const lead = this.selectedDetail();
    if (!lead || this.detailBusy()) {
      return;
    }
    const value = this.followUpControl.value || null;
    this.detailBusy.set(true);
    this.service.setFollowUp(lead.id, { followUpDate: value }).subscribe({
      next: (updated) => {
        this.detailBusy.set(false);
        this.selectedDetail.set(updated);
        this.toasts.success(value ? 'Follow-up date set.' : 'Follow-up cleared.');
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.detailBusy.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not update the follow-up date.');
      },
    });
  }

  clearFollowUp(): void {
    this.followUpControl.setValue('');
    this.saveFollowUp();
  }

  // --- Convert ------------------------------------------------------------

  /**
   * Starts the convert flow (Req 4, design §Convert Flow): navigates to the New
   * Order form pre-filled from this lead ({@code /orders/new?leadId=}). The New
   * Order form seeds the customer identity from the lead and, on save, posts
   * {@code POST /api/leads/{id}/convert} (which creates the order and marks the
   * lead WON) instead of the plain order-create endpoint.
   */
  convert(lead: LeadDetail): void {
    void this.router.navigate(['/orders/new'], { queryParams: { leadId: lead.id } });
  }

  // --- Helpers ------------------------------------------------------------

  /** Two-letter initials for the customer avatar chip. */
  customerInitials(name: string | null | undefined): string {
    const parts = (name ?? '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      return '?';
    }
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  sourceLabel(source: LeadSource): string {
    return this.leadSourceOptions.find((o) => o.value === source)?.label ?? source;
  }

  lostReasonLabel(reason: LostReason | null | undefined): string {
    if (!reason) {
      return '';
    }
    return this.lostReasonOptions.find((o) => o.value === reason)?.label ?? reason;
  }

  private messageOf(err: HttpErrorResponse): string | null {
    const body = err.error as ApiError | undefined;
    return body?.message ?? null;
  }
}
