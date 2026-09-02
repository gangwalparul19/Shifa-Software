import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError } from 'core';
import {
  ID_PROOF_TYPES,
  IdProofType,
} from '../salespeople/salespeople.service';
import {
  MyProfile,
  MyProfileService,
  ProfileChangeRequest,
  SubmitProfileChangeRequest,
} from './my-profile.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { verificationBadgeClass } from '../shared/status-badge.component';
import { roleLabel } from '../shared/role-label';

/**
 * Self-service "My Profile" for the signed-in staff member (any role).
 *
 * <p>Shows their current details + photo, and lets them submit a change request
 * that goes to an admin for approval. The account is NOT changed until an admin
 * approves — a pending request is shown as a clear "awaiting approval" banner.
 */
@Component({
  selector: 'admin-my-profile',
  standalone: true,
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './my-profile.component.html',
  styleUrl: './my-profile.component.css',
})
export class MyProfileComponent implements OnInit, OnDestroy {
  private readonly service = inject(MyProfileService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);

  protected readonly idProofTypes = ID_PROOF_TYPES;

  protected readonly data = signal<MyProfile | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly formError = signal<string | null>(null);
  protected readonly photoUrl = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    mobile: ['', [Validators.pattern(/^$|^[0-9]{10}$/)]],
    dateOfBirth: [''],
    address: ['', [Validators.maxLength(500)]],
    idProofType: ['' as IdProofType | ''],
    idProofNumber: ['', [Validators.maxLength(60)]],
    requestNote: ['', [Validators.maxLength(500)]],
  });

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    this.clearPhoto();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.getMine().subscribe({
      next: (data) => {
        this.data.set(data);
        this.prefill(data);
        this.loading.set(false);
        if (data.profile.hasProfileImage) {
          this.loadPhoto();
        }
      },
      error: () => {
        this.loadError.set('Could not load your profile. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Prefill the form with the pending proposal if present, else the current profile. */
  private prefill(data: MyProfile): void {
    const src = data.pending?.proposed ?? {
      fullName: data.profile.fullName,
      email: data.profile.email,
      mobile: data.profile.mobile,
      dateOfBirth: data.profile.dateOfBirth,
      address: data.profile.address,
      idProofType: data.profile.idProofType,
      idProofNumber: data.profile.idProofNumber,
    };
    this.form.reset({
      fullName: src.fullName ?? '',
      email: src.email ?? '',
      mobile: src.mobile ?? '',
      dateOfBirth: src.dateOfBirth ?? '',
      address: src.address ?? '',
      idProofType: src.idProofType ?? '',
      idProofNumber: src.idProofNumber ?? '',
      requestNote: data.pending?.requestNote ?? '',
    });
  }

  private loadPhoto(): void {
    this.service.photo().subscribe({
      next: (blob) => {
        this.clearPhoto();
        this.photoUrl.set(URL.createObjectURL(blob));
      },
      error: () => this.clearPhoto(),
    });
  }

  private clearPhoto(): void {
    const url = this.photoUrl();
    if (url) {
      URL.revokeObjectURL(url);
    }
    this.photoUrl.set(null);
  }

  submit(): void {
    if (this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const request: SubmitProfileChangeRequest = {
      fullName: raw.fullName.trim(),
      email: raw.email.trim() || null,
      mobile: raw.mobile.trim() || null,
      dateOfBirth: raw.dateOfBirth || null,
      address: raw.address.trim() || null,
      idProofType: (raw.idProofType || null) as IdProofType | null,
      idProofNumber: raw.idProofNumber.trim() || null,
      requestNote: raw.requestNote.trim() || null,
    };
    this.saving.set(true);
    this.formError.set(null);
    this.service.submit(request).subscribe({
      next: () => {
        this.saving.set(false);
        this.toasts.success('Changes submitted for admin approval.');
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.saving.set(false);
        this.formError.set(this.describeError(err));
      },
    });
  }

  // --- View helpers -------------------------------------------------------

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

  /** Human role label (shared, single source). */
  readonly roleLabel = roleLabel;

  verificationLabel(status: string): string {
    switch (status) {
      case 'VERIFIED':
        return 'Verified';
      case 'REJECTED':
        return 'Rejected';
      default:
        return 'Pending verification';
    }
  }

  /** Canonical verification badge tone (shared, brand palette). */
  readonly verificationBadgeClass = verificationBadgeClass;

  /** Tone token for the hero badge (drives a solid, high-contrast pill on the green banner). */
  verificationTone(status: string): string {
    switch (status) {
      case 'VERIFIED':
        return 'verified';
      case 'REJECTED':
        return 'rejected';
      default:
        return 'pending';
    }
  }

  verificationIcon(status: string): string {
    switch (status) {
      case 'VERIFIED':
        return 'ti-shield-check';
      case 'REJECTED':
        return 'ti-shield-x';
      default:
        return 'ti-shield-half';
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

  /** Fields that differ between current and proposed (for the pending diff). */
  changedFields(pending: ProfileChangeRequest): { label: string; from: string; to: string }[] {
    const c = pending.current;
    const p = pending.proposed;
    const rows: { label: string; from: string; to: string }[] = [];
    const add = (label: string, from: string | null, to: string | null) => {
      const f = from ?? '';
      const t = to ?? '';
      if (f !== t) {
        rows.push({ label, from: f || '—', to: t || '—' });
      }
    };
    add('Full name', c.fullName, p.fullName);
    add('Mobile', c.mobile, p.mobile);
    add('Email', c.email, p.email);
    add('Date of birth', c.dateOfBirth, p.dateOfBirth);
    add('Address', c.address, p.address);
    add('ID proof type', this.idProofLabel(c.idProofType), this.idProofLabel(p.idProofType));
    add('ID proof number', c.idProofNumber, p.idProofNumber);
    return rows;
  }

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Something went wrong. Please try again.';
  }
}
