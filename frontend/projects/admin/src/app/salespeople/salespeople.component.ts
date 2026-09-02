import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from 'core';
import {
  ID_PROOF_TYPES,
  IdProofType,
  SalespeopleService,
  SalespersonPerformanceDetail,
  SalespersonPerformanceSummary,
  StaffProfile,
  UpdateStaffProfileRequest,
  VerificationStatus,
} from './salespeople.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { verificationBadgeClass } from '../shared/status-badge.component';

/** The verification filter tabs, in display order. */
type StatusFilter = 'ALL' | VerificationStatus;

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
  imports: [ReactiveFormsModule, DatePipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './salespeople.component.html',
  styleUrl: './salespeople.component.css',
})
export class SalespeopleComponent implements OnInit {
  private readonly service = inject(SalespeopleService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);

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
  }

  setSort(key: 'revenueThisMonth' | 'ordersThisMonth' | 'successRate' | 'name'): void {
    this.sortKey.set(key);
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

  /** The tallest daily order count in the trend, for bar scaling (min 1). */
  trendMax(): number {
    const t = this.detail()?.trend ?? [];
    return Math.max(1, ...t.map((p) => p.orders));
  }

  // --- Drawer -------------------------------------------------------------

  open(member: StaffProfile): void {
    this.formError.set(null);
    this.drawerTab.set('performance');
    this.selected.set(member);
    this.verifyNote.reset('');
    this.clearProfilePreview();
    // Load the Salesperson 360 detail for the performance section.
    this.detail.set(null);
    this.detailLoading.set(true);
    this.service.performance(member.id).subscribe({
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
    return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
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
