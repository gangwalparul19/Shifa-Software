import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ApiError } from 'core';
import {
  IdProofType,
  ProfileChangeRequest,
  SalespeopleService,
} from '../salespeople/salespeople.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';

/**
 * Admin approval queue for self-service staff profile change requests.
 *
 * <p>Each pending request shows a field-by-field diff (current → requested) and
 * Approve / Reject actions with an optional note. Approving applies the changes
 * to the employee's account; rejecting leaves it unchanged. ADMIN-only.
 */
@Component({
  selector: 'admin-profile-approvals',
  standalone: true,
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './profile-approvals.component.html',
  styleUrl: './profile-approvals.component.css',
})
export class ProfileApprovalsComponent implements OnInit {
  private readonly service = inject(SalespeopleService);
  private readonly fb = inject(FormBuilder);
  private readonly toasts = inject(ToastService);

  protected readonly requests = signal<ProfileChangeRequest[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly actioningId = signal<number | null>(null);

  /** Per-request reviewer note, keyed by request id. */
  protected readonly notes = this.fb.record<string>({});

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.changeRequests().subscribe({
      next: (items) => {
        this.requests.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load change requests. Please try again.');
        this.loading.set(false);
      },
    });
  }

  noteFor(id: number): string {
    return (this.notes.controls[id]?.value ?? '').toString();
  }

  onNoteInput(id: number, event: Event): void {
    const value = (event.target as HTMLTextAreaElement).value;
    if (this.notes.controls[id]) {
      this.notes.controls[id].setValue(value);
    } else {
      this.notes.addControl(id.toString(), this.fb.nonNullable.control(value));
    }
  }

  decide(req: ProfileChangeRequest, status: 'APPROVED' | 'REJECTED'): void {
    if (this.actioningId() !== null) {
      return;
    }
    this.actioningId.set(req.id);
    const note = this.noteFor(req.id).trim() || null;
    this.service.reviewChangeRequest(req.id, status, note).subscribe({
      next: () => {
        this.actioningId.set(null);
        this.toasts.success(
          status === 'APPROVED'
            ? `Approved — ${req.username}'s profile updated.`
            : `Rejected ${req.username}'s request.`,
        );
        this.requests.update((list) => list.filter((r) => r.id !== req.id));
      },
      error: (err: HttpErrorResponse) => {
        this.actioningId.set(null);
        this.toasts.error(this.describeError(err));
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

  /** Fields that differ between current and proposed. */
  changedFields(req: ProfileChangeRequest): { label: string; from: string; to: string }[] {
    const c = req.current;
    const p = req.proposed;
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
