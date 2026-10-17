import { IstDatePipe } from '../shared/ist-date.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, HostListener, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  Validators,
} from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged, takeUntil } from 'rxjs';
import { ApiError, AuthService, Role } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { PincodeService } from '../shared/pincode.service';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { LEAD_SOURCE_OPTIONS, LeadSource } from '../orders/orders.model';
import { LeadsService } from './leads.service';
import {
  AssignableUser,
  CreateLeadRequest,
  LEAD_STAGE_ORDER,
  LEAD_STATUS_LABELS,
  LeadDetail,
  LeadStatus,
  LeadSummary,
  LeadTag,
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
    IstDatePipe,
    PageHeaderComponent,
    PaginationComponent,
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
  private readonly route = inject(ActivatedRoute);
  private readonly pincodeService = inject(PincodeService);

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
  /** Admin-created filter: '' = all, 'true' = Lead by Admin, 'false' = user-captured (Req 3.3). */
  protected readonly adminFilter = new FormControl<string>('', { nonNullable: true });
  /** Tag name filter (client-side over the loaded list; Req 4.5). */
  protected readonly tagFilter = signal<string>('');

  /** Whether the custom source-filter dropdown is open. */
  protected readonly sourceOpen = signal(false);

  /** The loaded leads filtered by the active status lens + tag filter. */
  protected readonly visibleLeads = computed<LeadSummary[]>(() => {
    const lens = this.statusLens();
    const tag = this.tagFilter();
    let rows = this.leads();
    if (lens !== 'ALL') {
      rows = rows.filter((l) => l.status === lens);
    }
    if (tag) {
      rows = rows.filter((l) => (l.tags ?? []).includes(tag));
    }
    return rows;
  });

  // --- Client-side paging (over the post-filter flat list) ----------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('leads', 10));
  protected readonly totalElements = computed(() => this.visibleLeads().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed(() => {
    const s = this.page() * this.size();
    return this.visibleLeads().slice(s, s + this.size());
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
    () =>
      !!this.search.value.trim() ||
      !!this.sourceFilter.value ||
      !!this.adminFilter.value ||
      !!this.tagFilter(),
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
    followUpTime: new FormControl<string>('', { nonNullable: true }),
    // CRM expansion Req 7: optional shipping address (pre-fills conversion).
    addressLine: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(250)],
    }),
    city: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(100)],
    }),
    state: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.maxLength(100)],
    }),
    postalCode: new FormControl<string>('', {
      nonNullable: true,
      validators: [Validators.pattern(/^\d{6}$/)],
    }),
  });

  /** Whether the OTHER free-text source note field is shown (Req 1.2). */
  protected readonly showSourceNote = signal(false);

  /** "Detected: <city>, <state>" hint shown under the pincode field (Req 7). */
  protected readonly detectedLocation = signal<string | null>(null);
  /** True while a pincode lookup is in flight (shows a tiny spinner). */
  protected readonly pincodeLooking = signal(false);

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

  /** Whether the current user may assign/reassign leads (ADMIN + TEAM_LEAD; Req 1.5). */
  protected readonly canAssign = computed(() =>
    this.auth.hasAnyRole(Role.ADMIN, Role.TEAM_LEAD),
  );

  /** Whether the current user may bulk-import leads (ADMIN only; Req 2.5). */
  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(Role.ADMIN));

  // --- Tags (CRM expansion Req 4) ----------------------------------------
  protected readonly allTags = signal<LeadTag[]>([]);

  // --- Bulk-select + assign (CRM expansion Req 1) -------------------------
  /** The lead ids currently selected for a bulk action. */
  protected readonly selectedIds = signal<Set<number>>(new Set());
  protected readonly selectionCount = computed(() => this.selectedIds().size);
  /** Candidate assignees the caller may assign to (ADMIN: all; TEAM_LEAD: own team). */
  protected readonly assignableUsers = signal<AssignableUser[]>([]);
  protected readonly assignOpen = signal(false);
  protected readonly assignBusy = signal(false);
  /** The assign target: either a single lead (drawer) or the bulk selection. */
  protected readonly assignLeadId = signal<number | null>(null);
  protected readonly assignUserControl = new FormControl<string>('', { nonNullable: true });

  // --- Import leads (CRM expansion Req 2) --------------------------------
  protected readonly importBusy = signal(false);
  protected readonly importResult = signal<{ created: number; skipped: { row: number; reason: string }[] } | null>(null);
  protected readonly importError = signal<string | null>(null);

  // --- Tag editor in the drawer ------------------------------------------
  protected readonly tagToAdd = new FormControl<string>('', { nonNullable: true });
  protected readonly followUpTimeControl = new FormControl<string>('', { nonNullable: true });

  ngOnInit(): void {
    this.load();
    this.loadPipeline();
    this.loadTags();
    if (this.canAssign()) {
      this.loadAssignableUsers();
    }
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
    this.adminFilter.valueChanges
      .pipe(takeUntil(this.destroy$))
      .subscribe(() => this.load());

    // CRM expansion Req 7: when a 6-digit pincode is typed in the capture/edit
    // form, look up its city + state via the India Post API and pre-fill those
    // fields. Best-effort — a failed/blocked lookup just leaves them for manual
    // entry and never blocks the form (mirrors the New Order form).
    this.leadForm.controls.postalCode.valueChanges
      .pipe(debounceTime(400), distinctUntilChanged(), takeUntil(this.destroy$))
      .subscribe((pincode) => this.autoFillFromPincode(pincode));

    // Deep-link: /leads?leadId=N opens that lead's detail drawer directly
    // (e.g. tapping a row on the Due follow-ups page).
    const leadIdParam = this.route.snapshot.queryParamMap.get('leadId');
    const leadId = leadIdParam ? Number(leadIdParam) : NaN;
    if (Number.isFinite(leadId) && leadId > 0) {
      this.openDetailById(leadId);
    }
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  // --- Loading ------------------------------------------------------------

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const adminValue = this.adminFilter.value;
    this.service
      .list({
        q: this.search.value,
        source: (this.sourceFilter.value || null) as LeadSource | null,
        createdByAdmin: adminValue === '' ? null : adminValue === 'true',
      })
      .subscribe({
        next: (rows) => {
          this.leads.set(rows);
          this.page.set(0);
          this.selectedIds.set(new Set());
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

  /** Loads the active tag vocabulary for the filter + drawer tag editor (Req 4). */
  loadTags(): void {
    this.service.listTags().subscribe({
      next: (tags) => this.allTags.set(tags),
      error: () => {
        /* tags are optional UI sugar; a failure just hides them */
      },
    });
  }

  /**
   * Loads the candidate assignees the caller may assign leads to, from the
   * team-scoped {@code /api/leads/assignable-users} endpoint (Req 1): ADMIN gets
   * all active salespeople + team leads; a TEAM_LEAD gets only their own team.
   * (The admin users endpoint is ADMIN-only, so it left the picker empty for a
   * team lead.)
   */
  loadAssignableUsers(): void {
    this.service.assignableUsers().subscribe({
      next: (users) => this.assignableUsers.set(users),
      error: () => {
        /* non-fatal: the assign picker simply shows no users */
      },
    });
  }

  /** The display name for an owner id, resolved from the loaded user list. */
  ownerName(userId: number | null | undefined): string {
    if (userId == null) {
      return '—';
    }
    const user = this.assignableUsers().find((u) => u.id === userId);
    return user ? user.name : `User #${userId}`;
  }

  // --- Tag filter ---------------------------------------------------------

  setTagFilter(tag: string): void {
    this.tagFilter.set(this.tagFilter() === tag ? '' : tag);
    this.page.set(0);
  }

  setAdminFilter(value: string): void {
    this.adminFilter.setValue(value);
  }

  // --- Bulk selection -----------------------------------------------------

  isSelected(id: number): boolean {
    return this.selectedIds().has(id);
  }

  toggleSelected(id: number, event?: Event): void {
    event?.stopPropagation();
    const next = new Set(this.selectedIds());
    if (next.has(id)) {
      next.delete(id);
    } else {
      next.add(id);
    }
    this.selectedIds.set(next);
  }

  clearSelection(): void {
    this.selectedIds.set(new Set());
  }

  // --- Assign / reassign --------------------------------------------------

  /** Opens the assign picker for a single lead (from the drawer). */
  openAssign(leadId: number): void {
    this.assignLeadId.set(leadId);
    this.assignUserControl.setValue('');
    this.assignOpen.set(true);
  }

  /** Opens the assign picker for the current bulk selection. */
  openBulkAssign(): void {
    if (this.selectionCount() === 0) {
      return;
    }
    this.assignLeadId.set(null);
    this.assignUserControl.setValue('');
    this.assignOpen.set(true);
  }

  closeAssign(): void {
    this.assignOpen.set(false);
  }

  submitAssign(): void {
    const raw = this.assignUserControl.value;
    if (!raw || this.assignBusy()) {
      return;
    }
    const toUserId = Number(raw);
    this.assignBusy.set(true);
    const single = this.assignLeadId();
    if (single != null) {
      this.service.assign(single, toUserId).subscribe({
        next: (updated) => {
          this.assignBusy.set(false);
          this.assignOpen.set(false);
          if (this.selectedDetail()?.id === single) {
            this.selectedDetail.set(updated);
          }
          this.toasts.success('Lead reassigned.');
          this.load();
        },
        error: (err: HttpErrorResponse) => {
          this.assignBusy.set(false);
          this.toasts.error(this.messageOf(err) ?? 'Could not reassign the lead.');
        },
      });
      return;
    }
    const ids = Array.from(this.selectedIds());
    this.service.bulkAssign(ids, toUserId).subscribe({
      next: (result) => {
        this.assignBusy.set(false);
        this.assignOpen.set(false);
        const skipped = result.skipped.length;
        this.toasts.success(
          `Reassigned ${result.succeeded.length} lead(s)` +
            (skipped ? `, ${skipped} skipped.` : '.'),
        );
        this.clearSelection();
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.assignBusy.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not reassign the leads.');
      },
    });
  }

  // --- Import leads (ADMIN) ----------------------------------------------

  onImportFile(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.importBusy.set(true);
    this.importError.set(null);
    this.importResult.set(null);
    this.service.importLeads(file).subscribe({
      next: (result) => {
        this.importBusy.set(false);
        this.importResult.set(result);
        this.toasts.success(
          `Imported ${result.created} lead(s)` +
            (result.skipped.length ? `, ${result.skipped.length} skipped.` : '.'),
        );
        this.load();
        this.loadPipeline();
      },
      error: (err: HttpErrorResponse) => {
        this.importBusy.set(false);
        this.importError.set(this.messageOf(err) ?? 'Could not import the file.');
      },
    });
    input.value = '';
  }

  dismissImportResult(): void {
    this.importResult.set(null);
    this.importError.set(null);
  }

  // --- Drawer tag add/remove (allowed on terminal leads; Req 4, 5.1) ------

  addTagToLead(): void {
    const lead = this.selectedDetail();
    const raw = this.tagToAdd.value;
    if (!lead || !raw || this.detailBusy()) {
      return;
    }
    const tagId = Number(raw);
    this.detailBusy.set(true);
    this.service.addTag(lead.id, tagId).subscribe({
      next: (updated) => {
        this.detailBusy.set(false);
        this.selectedDetail.set(updated);
        this.tagToAdd.setValue('');
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.detailBusy.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not add the tag.');
      },
    });
  }

  removeTagFromLead(tagId: number): void {
    const lead = this.selectedDetail();
    if (!lead || this.detailBusy()) {
      return;
    }
    this.detailBusy.set(true);
    this.service.removeTag(lead.id, tagId).subscribe({
      next: (updated) => {
        this.detailBusy.set(false);
        this.selectedDetail.set(updated);
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.detailBusy.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not remove the tag.');
      },
    });
  }

  /** Tags not yet applied to the open lead (for the add dropdown). */
  availableTagsFor(lead: LeadDetail | null): LeadTag[] {
    if (!lead) {
      return this.allTags();
    }
    const applied = new Set((lead.tags ?? []).map((t) => t.id));
    return this.allTags().filter((t) => !applied.has(t.id));
  }

  setLens(lens: StatusLens): void {
    this.statusLens.set(lens);
    this.page.set(0);
  }

  clearSearch(): void {
    this.search.setValue('');
  }

  // --- Paging handlers ----------------------------------------------------
  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('leads', s);
    this.page.set(0);
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
      followUpTime: '',
      addressLine: '',
      city: '',
      state: '',
      postalCode: '',
    });
    this.showSourceNote.set(false);
    this.detectedLocation.set(null);
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
      followUpTime: (lead.followUpTime ?? '').slice(0, 5),
      addressLine: lead.addressLine ?? '',
      city: lead.city ?? '',
      state: lead.state ?? '',
      postalCode: lead.postalCode ?? '',
    });
    this.showSourceNote.set(lead.leadSource === 'OTHER');
    this.detectedLocation.set(null);
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
    const addressLine = raw.addressLine.trim();
    const city = raw.city.trim();
    const state = raw.state.trim();
    const postalCode = raw.postalCode.trim();
    const payload: CreateLeadRequest = {
      customerName: raw.customerName.trim(),
      leadSource: raw.leadSource as LeadSource,
      ...(isOther && note ? { leadSourceNote: note } : {}),
      ...(mobile ? { customerMobile: mobile } : {}),
      ...(email ? { customerEmail: email } : {}),
      ...(workingNote ? { note: workingNote } : {}),
      ...(raw.followUpDate ? { followUpDate: raw.followUpDate } : { followUpDate: null }),
      ...(raw.followUpDate && raw.followUpTime ? { followUpTime: raw.followUpTime } : {}),
      // CRM expansion Req 7: optional shipping address.
      ...(addressLine ? { addressLine } : {}),
      ...(city ? { city } : {}),
      ...(state ? { state } : {}),
      ...(postalCode ? { postalCode } : {}),
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
    this.openDetailById(lead.id);
  }

  /** Opens the detail drawer for a lead id (used by the list + the `?leadId=` deep link). */
  openDetailById(id: number): void {
    this.detailLoading.set(true);
    this.detailError.set(null);
    this.selectedDetail.set(null);
    this.service.detail(id).subscribe({
      next: (detail) => {
        this.selectedDetail.set(detail);
        this.followUpControl.setValue(detail.followUpDate ?? '');
        this.followUpTimeControl.setValue((detail.followUpTime ?? '').slice(0, 5));
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
      this.followUpTimeControl.setValue((d.followUpTime ?? '').slice(0, 5));
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
    const time = value ? this.followUpTimeControl.value || null : null;
    this.detailBusy.set(true);
    this.service.setFollowUp(lead.id, { followUpDate: value, followUpTime: time }).subscribe({
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
    this.followUpTimeControl.setValue('');
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

  // --- Pincode auto-fill (Req 7) ------------------------------------------

  /**
   * Resolves a 6-digit pincode to city + state (CRM expansion, Req 7) and
   * pre-fills those fields. To avoid clobbering what the user typed it only fills
   * City/State when they are currently empty; the "Detected" hint is always shown.
   * Entirely best-effort — a malformed/blocked/no-match lookup clears the hint.
   */
  private autoFillFromPincode(pincode: string | null): void {
    if (!pincode || !/^\d{6}$/.test(pincode)) {
      this.detectedLocation.set(null);
      this.pincodeLooking.set(false);
      return;
    }
    this.pincodeLooking.set(true);
    this.pincodeService.lookup(pincode).subscribe({
      next: (location) => {
        this.pincodeLooking.set(false);
        if (!location) {
          this.detectedLocation.set(null);
          return;
        }
        this.detectedLocation.set(`${location.city}, ${location.state}`);
        const city = this.leadForm.controls.city;
        const state = this.leadForm.controls.state;
        if (!city.value.trim()) {
          city.setValue(location.city);
          city.markAsDirty();
        }
        if (!state.value.trim()) {
          state.setValue(location.state);
          state.markAsDirty();
        }
      },
      error: () => {
        this.pincodeLooking.set(false);
        this.detectedLocation.set(null);
      },
    });
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

  /** "City, State — PIN" line for the lead-detail address block (empty parts dropped). */
  cityStateLine(lead: LeadDetail): string {
    const place = [lead.city, lead.state].filter((p) => !!p && p.trim()).join(', ');
    const pin = lead.postalCode?.trim();
    return [place, pin].filter(Boolean).join(' — ');
  }

  /** A Tabler brand/context icon for a lead source (or the "All sources" broadcast icon). */
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

  /** The label for the current source-filter selection (button face). */
  sourceButtonLabel(): string {
    const value = this.sourceFilter.value;
    return value ? this.sourceLabel(value as LeadSource) : 'All sources';
  }

  /** Toggle the custom source-filter dropdown. */
  toggleSourceMenu(): void {
    this.sourceOpen.update((open) => !open);
  }

  /** Pick a source from the custom dropdown and close it. */
  selectSource(value: string): void {
    this.sourceFilter.setValue(value);
    this.sourceOpen.set(false);
  }

  /** Close the source dropdown when clicking anywhere outside it. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (this.sourceOpen() && !(event.target as HTMLElement).closest('.lead-source-dd')) {
      this.sourceOpen.set(false);
    }
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
