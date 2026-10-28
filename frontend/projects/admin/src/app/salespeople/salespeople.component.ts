import { IstDatePipe } from '../shared/ist-date.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError, AuthService, Role } from 'core';
import {
  ID_PROOF_TYPES,
  IdProofType,
  PerformanceExportFormat,
  PerformancePeriod,
  PerformanceDownload,
  SalespeopleService,
  SalespersonPerformanceDetail,
  SalespersonPerformanceSummary,
  StaffProfile,
  UpdateStaffProfileRequest,
  VerificationStatus,
} from './salespeople.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { verificationBadgeClass } from '../shared/status-badge.component';

/** The verification filter tabs, in display order. */
type StatusFilter = 'ALL' | VerificationStatus;

/** Period lens for the Performance drawer (sales & orders per day). */
type PerfPeriod = PerformancePeriod;

/** One day's sales + orders within the selected period. */
export interface PerfDayRow {
  date: string;
  orders: number;
  revenue: number;
}

/** Aggregated totals for the selected period. */
export interface PerfPeriodStats {
  label: string;
  orders: number;
  /** Sales = sum of revenue EXCLUDING cancelled/rejected orders. */
  sales: number;
  days: PerfDayRow[];
}

/**
 * Admin-only "All salespeople" directory with onboarding profiles &amp; ID
 * verification.
 *
 * <p>Lists every salesperson with their verification state at a glance, and
 * opens a drawer to capture their full profile, upload/view their ID proof
 * document, and record a verify/reject decision. Backed by
 * {@code /api/admin/staff}; route + nav are ADMIN-guarded.
 */
@Component({
  selector: 'admin-salespeople',
  standalone: true,
  imports: [ReactiveFormsModule, IstDatePipe, PageHeaderComponent, PaginationComponent, StatePanelComponent],
  templateUrl: './salespeople.component.html',
  styleUrl: './salespeople.component.css',
})
export class SalespeopleComponent implements OnInit {
  private readonly service = inject(SalespeopleService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  /** Admins get the "View orders" drill-down on each card. */
  protected readonly isAdmin = computed(() => this.auth.hasAnyRole(Role.ADMIN));

  protected readonly idProofTypes = ID_PROOF_TYPES;

  protected readonly staff = signal<StaffProfile[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);

  // --- Salesperson 360 (performance) --------------------------------------
  /** Leaderboard metrics indexed by salesperson id (for list KPIs + sorting). */
  protected readonly perfById = signal<Map<number, SalespersonPerformanceSummary>>(new Map());
  /** How the directory list is sorted. */
  protected readonly sortKey = signal<'revenueThisMonth' | 'ordersThisMonth' | 'successRate' | 'name'>(
    'revenueThisMonth',
  );
  /** The open salesperson's full 360 detail (loaded when the drawer opens). */
  protected readonly detail = signal<SalespersonPerformanceDetail | null>(null);
  protected readonly detailLoading = signal(false);

  /** Performance period filter for the drawer (sales/orders per day). */
  protected readonly perfPeriod = signal<PerfPeriod>('MONTH');
  /** Period tabs rendered in the Performance drawer, in display order. */
  protected readonly perfPeriodTabs: { key: PerfPeriod; label: string }[] = [
    { key: 'TODAY', label: 'Today' },
    { key: 'YESTERDAY', label: 'Yesterday' },
    { key: 'WEEK', label: 'This Week' },
    { key: 'MONTH', label: 'This Month' },
    { key: 'OVERALL', label: 'Overall' },
  ];
  /** Trend window (days) fetched for the drawer — wide enough for "This Month". */
  private readonly PERF_TREND_DAYS = 60;

  /** Period used by the directory-level team export. */
  protected readonly reportPeriod = signal<PerformancePeriod>('MONTH');
  /** Prevents duplicate downloads from either export toolbar. */
  protected readonly exporting = signal<PerformanceExportFormat | null>(null);

  /** Verification filter tab. */
  protected readonly filter = signal<StatusFilter>('ALL');


  /** The filter tabs rendered above the list, in display order. */
  protected readonly filterTabs: StatusFilter[] = ['ALL', 'PENDING', 'VERIFIED', 'REJECTED'];

  /** Count for a filter tab. */
  countFor(tab: StatusFilter): number {
    return this.counts()[tab];
  }

  /** Human label for a filter tab (handles the "All" pseudo-status). */
  tabLabel(tab: StatusFilter): string {
    return tab === 'ALL' ? 'All' : this.statusLabel(tab);
  }

  protected readonly counts = computed(() => {
    const list = this.staff();
    return {
      ALL: list.length,
      PENDING: list.filter((s) => s.verificationStatus === 'PENDING').length,
      VERIFIED: list.filter((s) => s.verificationStatus === 'VERIFIED').length,
      REJECTED: list.filter((s) => s.verificationStatus === 'REJECTED').length,
    };
  });

  protected readonly filtered = computed(() => {
    const f = this.filter();
    const base = f === 'ALL' ? this.staff() : this.staff().filter((s) => s.verificationStatus === f);
    const perf = this.perfById();
    const key = this.sortKey();
    const list = [...base];
    if (key === 'name') {
      list.sort((a, b) => (a.fullName || a.username).localeCompare(b.fullName || b.username));
    } else {
      list.sort((a, b) => this.metric(perf, b.id, key) - this.metric(perf, a.id, key));
    }
    return list;
  });

  // --- Client-side paging (over the filtered/sorted list) -----------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('salespeople', 10));
  protected readonly totalElements = computed(() => this.filtered().length);
  protected readonly totalPages = computed(() => Math.max(1, Math.ceil(this.totalElements() / this.size())));
  protected readonly pageItems = computed<StaffProfile[]>(() => {
    const s = this.page() * this.size();
    return this.filtered().slice(s, s + this.size());
  });

  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('salespeople', s);
    this.page.set(0);
  }

  private metric(
    perf: Map<number, SalespersonPerformanceSummary>,
    id: number,
    key: 'revenueThisMonth' | 'ordersThisMonth' | 'successRate',
  ): number {
    const p = perf.get(id);
    if (!p) {
      return -1;
    }
    if (key === 'revenueThisMonth') {
      return Number(p.revenueThisMonth) || 0;
    }
    if (key === 'ordersThisMonth') {
      return p.ordersThisMonth;
    }
    return p.successRate;
  }

  /** The staff member open in the drawer; null when closed. */
  protected readonly selected = signal<StaffProfile | null>(null);

  /**
   * Active tab within the drawer so its (long) content is split into
   * Performance / Profile / Verification tabs instead of one long scroll —
   * mirroring the step navigation on the New Order screen.
   */
  protected readonly drawerTab = signal<'performance' | 'profile' | 'verification'>('performance');
  protected readonly saving = signal(false);
  protected readonly uploading = signal(false);
  protected readonly uploadingPhoto = signal(false);
  protected readonly verifying = signal(false);
  protected readonly formError = signal<string | null>(null);

  /** Object URL of the selected member's profile photo (drawer preview); null when none. */
  protected readonly profilePreview = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    mobile: ['', [Validators.pattern(/^$|^[0-9]{10}$/)]],
    dateOfBirth: [''],
    address: ['', [Validators.maxLength(500)]],
    joinedOn: [''],
    idProofType: ['' as IdProofType | ''],
    idProofNumber: ['', [Validators.maxLength(60)]],
  });

  protected readonly verifyNote = this.fb.nonNullable.control('', [Validators.maxLength(500)]);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (items) => {
        this.staff.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load salespeople. Please try again.');
        this.loading.set(false);
      },
    });
    // Leaderboard metrics (non-fatal): powers the per-card KPIs + sorting.
    this.service.performanceLeaderboard().subscribe({
      next: (rows) => {
        const map = new Map<number, SalespersonPerformanceSummary>();
        rows.forEach((r) => map.set(r.id, r));
        this.perfById.set(map);
      },
      error: () => {
        /* non-fatal — cards just omit the KPIs */
      },
    });
  }

  setFilter(f: StatusFilter): void {
    this.filter.set(f);
    this.page.set(0);
  }

  setSort(key: 'revenueThisMonth' | 'ordersThisMonth' | 'successRate' | 'name'): void {
    this.sortKey.set(key);
    this.page.set(0);
  }

  /** Leaderboard summary for a salesperson (undefined until the board loads). */
  perfFor(id: number): SalespersonPerformanceSummary | undefined {
    return this.perfById().get(id);
  }

  /** Money display for a decimal string (₹, no decimals for compactness). */
  money(value: string | number | null | undefined): string {
    const n = Number(value ?? 0);
    return '₹' + Math.round(n).toLocaleString('en-IN');
  }

  /** Bootstrap text class reflecting a success/conversion rate. */
  rateClass(rate: number): string {
    if (rate >= 80) {
      return 'text-success';
    }
    if (rate >= 50) {
      return 'text-warning';
    }
    return 'text-danger';
  }

  /**
   * Colour for a card's delivery-success % that doesn't alarm on zero history:
   * only goes red/amber when there IS delivery history and the rate is low. A
   * brand-new salesperson with 0 delivered-yet orders shows neutral grey, not
   * red (missing history isn't a failure).
   */
  successClass(id: number): string {
    const p = this.perfFor(id);
    if (!p) {
      return 'text-secondary';
    }
    // No completed-delivery history yet → neutral, regardless of the 0% figure.
    const hasHistory = (p.deliveredCount ?? 0) + (p.failedCount ?? 0) > 0;
    if (!hasHistory) {
      return 'text-secondary';
    }
    return this.rateClass(p.successRate);
  }

  /**
   * ADMIN drill-down: open the Orders page scoped to this salesperson's orders
   * (?createdBy=&name=). Stops propagation so the card's own click (open drawer)
   * doesn't also fire.
   */
  viewOrders(member: StaffProfile, event: Event): void {
    event.stopPropagation();
    void this.router.navigate(['/orders'], {
      queryParams: { createdBy: member.id, name: member.fullName || member.username },
    });
  }

  /** The tallest daily order count in the trend, for bar scaling (min 1). */
  trendMax(): number {
    const days = this.periodStats()?.days ?? [];
    return Math.max(1, ...days.map((p) => p.orders));
  }

  /**
   * Orders + sales for the selected {@link perfPeriod}, derived from the daily
   * trend series (which the backend already returns with per-day revenue that
   * EXCLUDES cancelled/rejected orders). "Overall" uses the lifetime summary
   * totals since the trend window is capped at 60 days.
   */
  protected readonly periodStats = computed<PerfPeriodStats | null>(() => {
    const d = this.detail();
    if (!d) {
      return null;
    }
    const period = this.perfPeriod();
    const label = this.perfPeriodTabs.find((t) => t.key === period)?.label ?? '';

    if (period === 'OVERALL') {
      // Lifetime: use the summary totals (trend is only the last 60 days).
      return {
        label,
        orders: d.summary.ordersTotal,
        sales: Number(d.summary.revenueTotal ?? 0),
        days: [...d.trend]
          .map((p) => ({ date: p.date, orders: p.orders, revenue: Number(p.revenue ?? 0) }))
          .reverse(), // newest first for the breakdown list
      };
    }

    const today = new Date();
    today.setHours(0, 0, 0, 0);
    const inPeriod = (dateStr: string): boolean => {
      const dt = new Date(dateStr);
      dt.setHours(0, 0, 0, 0);
      const diffDays = Math.round((today.getTime() - dt.getTime()) / 86400000);
      switch (period) {
        case 'TODAY':
          return diffDays === 0;
        case 'YESTERDAY':
          return diffDays === 1;
        case 'WEEK': {
          // Current week, Monday-start.
          const dow = (today.getDay() + 6) % 7; // 0 = Monday
          return diffDays >= 0 && diffDays <= dow;
        }
        case 'MONTH':
          return dt.getMonth() === today.getMonth() && dt.getFullYear() === today.getFullYear();
        default:
          return false;
      }
    };

    const days: PerfDayRow[] = d.trend
      .filter((p) => inPeriod(p.date))
      .map((p) => ({ date: p.date, orders: p.orders, revenue: Number(p.revenue ?? 0) }))
      .reverse(); // newest first

    const orders = days.reduce((sum, p) => sum + p.orders, 0);
    const sales = days.reduce((sum, p) => sum + p.revenue, 0);
    return { label, orders, sales, days };
  });

  /** Switch the Performance period lens (no re-fetch; export uses server semantics). */
  setPerfPeriod(period: PerfPeriod): void {
    this.perfPeriod.set(period);
  }

  periodLabel(period: PerformancePeriod): string {
    return this.perfPeriodTabs.find((tab) => tab.key === period)?.label ?? period;
  }

  setReportPeriod(period: PerformancePeriod): void {
    this.reportPeriod.set(period);
  }

  /** Downloads the complete team report for the period selected above the directory. */
  exportDirectory(format: PerformanceExportFormat): void {
    this.downloadPerformance(format, this.reportPeriod());
  }

  /** Downloads the complete report for the salesperson currently in the drawer. */
  exportPerson(format: PerformanceExportFormat): void {
    const member = this.selected();
    if (!member) {
      return;
    }
    this.downloadPerformance(format, this.perfPeriod(), member.id);
  }

  private downloadPerformance(
    format: PerformanceExportFormat,
    period: PerformancePeriod,
    salespersonId?: number,
  ): void {
    if (this.exporting()) {
      return;
    }
    this.exporting.set(format);
    this.service.exportPerformance(format, period, salespersonId).subscribe({
      next: (result: PerformanceDownload) => {
        const url = URL.createObjectURL(result.blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = result.filename;
        document.body.appendChild(anchor);
        anchor.click();
        anchor.remove();
        setTimeout(() => URL.revokeObjectURL(url), 10_000);
        this.exporting.set(null);
        this.toasts.success(`Performance ${format.toUpperCase()} downloaded.`);
      },
      error: () => {
        this.exporting.set(null);
        this.toasts.error(`Could not export the performance ${format.toUpperCase()} report.`);
      },
    });
  }

  // --- Drawer -------------------------------------------------------------

  open(member: StaffProfile): void {
    this.formError.set(null);
    this.drawerTab.set('performance');
    this.perfPeriod.set('MONTH');
    this.selected.set(member);
    this.verifyNote.reset('');
    this.clearProfilePreview();
    // Load the Salesperson 360 detail for the performance section. Request a
    // wide trend window so the period lens (Today..This Month) has daily data.
    this.detail.set(null);
    this.detailLoading.set(true);
    this.service.performance(member.id, this.PERF_TREND_DAYS).subscribe({
      next: (d) => {
        this.detail.set(d);
        this.detailLoading.set(false);
      },
      error: () => this.detailLoading.set(false),
    });
    if (member.hasProfileImage) {
      this.loadProfilePreview(member.id);
    }
    this.form.reset({
      fullName: member.fullName,
      email: member.email ?? '',
      mobile: member.mobile ?? '',
      dateOfBirth: member.dateOfBirth ?? '',
      address: member.address ?? '',
      joinedOn: member.joinedOn ?? '',
      idProofType: member.idProofType ?? '',
      idProofNumber: member.idProofNumber ?? '',
    });
  }

  close(): void {
    this.selected.set(null);
    this.formError.set(null);
    this.detail.set(null);
    this.clearProfilePreview();
  }

  /** Fetches the profile photo blob and shows it as an object-URL preview. */
  private loadProfilePreview(id: number): void {
    this.service.profileImage(id).subscribe({
      next: (blob) => {
        this.clearProfilePreview();
        this.profilePreview.set(URL.createObjectURL(blob));
      },
      error: () => this.clearProfilePreview(),
    });
  }

  /** Revokes and clears any current preview object URL. */
  private clearProfilePreview(): void {
    const url = this.profilePreview();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.profilePreview.set(null);
  }

  /** Handles selecting a profile photo (image only; server compresses it before storage). */
  onProfileImageSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    const member = this.selected();
    if (!member) {
      return;
    }
    if (!file.type.startsWith('image/')) {
      this.toasts.error('The profile photo must be an image (JPG or PNG).');
      input.value = '';
      return;
    }
    this.uploadingPhoto.set(true);
    this.service.uploadProfileImage(member.id, file).subscribe({
      next: (updated) => {
        this.uploadingPhoto.set(false);
        input.value = '';
        this.applyUpdate(updated);
        this.loadProfilePreview(updated.id);
        this.toasts.success('Profile photo updated.');
      },
      error: (err: HttpErrorResponse) => {
        this.uploadingPhoto.set(false);
        input.value = '';
        this.toasts.error(this.describeError(err));
      },
    });
  }

  saveProfile(): void {
    const member = this.selected();
    if (!member || this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const request: UpdateStaffProfileRequest = {
      fullName: raw.fullName.trim(),
      email: raw.email.trim() || null,
      mobile: raw.mobile.trim() || null,
      dateOfBirth: raw.dateOfBirth || null,
      address: raw.address.trim() || null,
      joinedOn: raw.joinedOn || null,
      idProofType: (raw.idProofType || null) as IdProofType | null,
      idProofNumber: raw.idProofNumber.trim() || null,
    };
    this.saving.set(true);
    this.formError.set(null);
    this.service.updateProfile(member.id, request).subscribe({
      next: (updated) => {
        this.saving.set(false);
        this.applyUpdate(updated);
        this.toasts.success('Profile saved.');
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  onIdProofSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    const member = this.selected();
    if (!member) {
      return;
    }
    this.uploading.set(true);
    this.service.uploadIdProof(member.id, file).subscribe({
      next: (updated) => {
        this.uploading.set(false);
        input.value = '';
        this.applyUpdate(updated);
        this.toasts.success('ID proof uploaded. Awaiting verification.');
      },
      error: (err: HttpErrorResponse) => {
        this.uploading.set(false);
        input.value = '';
        this.toasts.error(this.describeError(err));
      },
    });
  }

  viewIdProof(): void {
    const member = this.selected();
    if (!member || !member.hasIdProof) {
      return;
    }
    this.service.idProof(member.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        window.open(url, '_blank');
        // Give the new tab time to read the blob before revoking.
        setTimeout(() => URL.revokeObjectURL(url), 60_000);
      },
      error: () => this.toasts.error('Could not open the ID proof.'),
    });
  }

  decide(status: Exclude<VerificationStatus, 'PENDING'>): void {
    const member = this.selected();
    if (!member || this.verifying()) {
      return;
    }
    if (this.verifyNote.invalid) {
      this.verifyNote.markAsTouched();
      return;
    }
    this.verifying.set(true);
    const note = this.verifyNote.getRawValue().trim() || null;
    this.service.verify(member.id, status, note).subscribe({
      next: (updated) => {
        this.verifying.set(false);
        this.applyUpdate(updated);
        this.toasts.success(status === 'VERIFIED' ? 'Salesperson verified.' : 'ID proof rejected.');
      },
      error: (err: HttpErrorResponse) => {
        this.verifying.set(false);
        this.toasts.error(this.describeError(err));
      },
    });
  }

  // --- View helpers -------------------------------------------------------

  /** Canonical verification badge tone (shared, brand palette). */
  readonly statusBadgeClass = verificationBadgeClass;

  statusLabel(status: VerificationStatus): string {
    switch (status) {
      case 'VERIFIED':
        return 'Verified';
      case 'REJECTED':
        return 'Rejected';
      default:
        return 'Pending';
    }
  }

  ownerRoleLabel(role: Role | string): string {
    if (role === Role.ADMIN || role === 'ADMIN') {
      return 'Admin';
    }
    if (role === Role.TEAM_LEAD || role === 'TEAM_LEAD') {
      return 'Team Lead';
    }
    return 'Salesperson';
  }
  idProofLabel(type: IdProofType | null): string {
    switch (type) {
      case 'AADHAAR':
        return 'Aadhaar';
      case 'PAN':
        return 'PAN';
      case 'DRIVING_LICENSE':
        return 'Driving licence';
      case 'VOTER_ID':
        return 'Voter ID';
      case 'PASSPORT':
        return 'Passport';
      case 'OTHER':
        return 'Other';
      default:
        return '—';
    }
  }

  initials(name: string): string {
    const parts = (name || '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      return '?';
    }
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  dateLabel(iso: string | null): string {
    if (!iso) {
      return '—';
    }
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) {
      return iso;
    }
    return d.toLocaleDateString('en-IN', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
      timeZone: 'Asia/Kolkata',
    });
  }

  /** Replaces the selected + list row with the server's updated copy. */
  private applyUpdate(updated: StaffProfile): void {
    this.selected.set(updated);
    this.staff.update((list) => list.map((s) => (s.id === updated.id ? updated : s)));
  }

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Something went wrong. Please try again.';
  }
}
