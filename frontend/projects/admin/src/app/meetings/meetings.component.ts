import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { ToastService } from '../shared/toast.service';
import { MeetingsService } from './meetings.service';
import {
  Meeting,
  MeetingMode,
  MEETING_MODES,
  MeetingStatus,
  meetingModeLabel,
  meetingStatusClass,
} from './meeting.model';

/**
 * CRM meetings (CRM expansion Phase 2, Task 11; Req 9): schedule / reschedule /
 * cancel / record-outcome. Owner-scoped server-side. Mobile-first cards + a
 * slide-in form; the outcome capture is a small inline prompt.
 */
@Component({
  selector: 'admin-meetings',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    IstDatePipe,
  ],
  templateUrl: './meetings.component.html',
  styleUrl: './meetings.component.css',
})
export class MeetingsComponent implements OnInit {
  private readonly service = inject(MeetingsService);
  private readonly toasts = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly modes = MEETING_MODES;
  protected readonly modeLabel = meetingModeLabel;
  protected readonly statusClass = meetingStatusClass;

  protected readonly meetings = signal<Meeting[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly statusFilter = signal<MeetingStatus | null>('SCHEDULED');
  protected readonly saving = signal(false);

  protected readonly drawerOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);

  // Outcome prompt.
  protected readonly outcomeFor = signal<Meeting | null>(null);
  protected readonly outcomeText = signal('');

  protected readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    startAt: ['', [Validators.required]],
    mode: ['CALL' as MeetingMode],
    location: ['', [Validators.maxLength(200)]],
    notes: ['', [Validators.maxLength(1000)]],
    leadId: [null as number | null],
    customerMobile: [''],
  });

  // Client-side paging.
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('meetings', 10));
  protected readonly totalElements = computed(() => this.meetings().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed(() => {
    const s = this.page() * this.size();
    return this.meetings().slice(s, s + this.size());
  });

  ngOnInit(): void {
    this.load();
  }

  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('meetings', s);
    this.page.set(0);
  }

  setFilter(status: MeetingStatus | null): void {
    this.statusFilter.set(status);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list(this.statusFilter()).subscribe({
      next: (rows) => {
        this.meetings.set(rows);
        this.page.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load your meetings. Please try again.');
        this.loading.set(false);
      },
    });
  }

  openCreate(): void {
    this.editingId.set(null);
    this.form.reset({ title: '', startAt: '', mode: 'CALL', location: '', notes: '', leadId: null, customerMobile: '' });
    this.drawerOpen.set(true);
  }

  openEdit(meeting: Meeting): void {
    this.editingId.set(meeting.id);
    this.form.reset({
      title: meeting.title,
      startAt: meeting.startAt ? meeting.startAt.slice(0, 16) : '',
      mode: meeting.mode ?? 'CALL',
      location: meeting.location ?? '',
      notes: meeting.notes ?? '',
      leadId: meeting.leadId ?? null,
      customerMobile: meeting.customerMobile ?? '',
    });
    this.drawerOpen.set(true);
  }

  closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const payload = {
      title: (v.title ?? '').trim(),
      startAt: v.startAt as string,
      mode: (v.mode as MeetingMode) ?? null,
      location: v.location?.trim() || null,
      notes: v.notes?.trim() || null,
      leadId: v.leadId ?? null,
      customerMobile: v.customerMobile?.trim() || null,
    };
    this.saving.set(true);
    const id = this.editingId();
    const req = id == null ? this.service.schedule(payload) : this.service.reschedule(id, payload);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.drawerOpen.set(false);
        this.toasts.success(id == null ? 'Meeting scheduled.' : 'Meeting rescheduled.');
        this.load();
      },
      error: () => {
        this.saving.set(false);
        this.toasts.error('Could not save the meeting. Please try again.');
      },
    });
  }

  cancel(meeting: Meeting, event: Event): void {
    event.stopPropagation();
    this.service.cancel(meeting.id).subscribe({
      next: () => {
        this.toasts.success('Meeting cancelled.');
        this.load();
      },
      error: () => this.toasts.error('Could not cancel the meeting.'),
    });
  }

  startOutcome(meeting: Meeting, event: Event): void {
    event.stopPropagation();
    this.outcomeFor.set(meeting);
    this.outcomeText.set('');
  }

  cancelOutcome(): void {
    this.outcomeFor.set(null);
  }

  saveOutcome(): void {
    const meeting = this.outcomeFor();
    const text = this.outcomeText().trim();
    if (!meeting || !text) {
      return;
    }
    this.service.recordOutcome(meeting.id, { outcome: text }).subscribe({
      next: () => {
        this.outcomeFor.set(null);
        this.toasts.success('Meeting outcome recorded.');
        this.load();
      },
      error: () => this.toasts.error('Could not record the outcome.'),
    });
  }

  onOutcomeInput(event: Event): void {
    this.outcomeText.set((event.target as HTMLTextAreaElement).value);
  }
}
