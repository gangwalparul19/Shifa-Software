import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { ConfirmService } from '../shared/confirm.service';
import { DialogDirective } from '../shared/dialog.directive';
import { ToastService } from '../shared/toast.service';
import {
  ReportRecipientOverview,
  ReportRecipientRow,
  ReportRecipientsService,
  ReportSendResult,
} from './report-recipients.service';

/** Today in India as yyyy-MM-dd, moved by `offsetDays` (the report is about an India calendar day). */
function indiaDate(offsetDays: number): string {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
  const [y, m, d] = parts.split('-').map(Number);
  const moved = new Date(Date.UTC(y, m - 1, d + offsetDays));
  return moved.toISOString().slice(0, 10);
}

/**
 * Settings → Email → "Admin report emails".
 *
 * Lists every active admin user first (some have no email on their profile, which is why they were
 * missing from the report), then the addresses added here, then any set on the server. An admin can
 * add more addresses, which are saved and used for every later report, and can send the morning report
 * right now to whichever of them are ticked.
 */
@Component({
  selector: 'admin-report-recipients-manager',
  standalone: true,
  imports: [ReactiveFormsModule, DialogDirective],
  styles: [
    `
      .rr-row { display: flex; gap: 0.75rem; align-items: flex-start; padding: 0.75rem 0; border-bottom: 1px solid #eef0ec; }
      .rr-row:last-child { border-bottom: 0; }
      .rr-icon { width: 2.25rem; height: 2.25rem; border-radius: 50%; display: grid; place-items: center; flex: 0 0 auto;
        background: #eef3ee; color: #1f5d3f; font-size: 1.1rem; }
      .rr-main { flex: 1 1 auto; min-width: 0; }
      .rr-name { font-weight: 600; overflow-wrap: anywhere; }
      .rr-email { overflow-wrap: anywhere; }
      .rr-actions { display: flex; gap: 0.25rem; align-items: center; flex: 0 0 auto; }
      .rr-pick { display: flex; gap: 0.6rem; align-items: flex-start; padding: 0.6rem 0.25rem; border-bottom: 1px solid #eef0ec; cursor: pointer; }
      .rr-pick.is-off { cursor: default; opacity: 0.6; }
      .rr-pick input { margin-top: 0.2rem; }
      .rr-modal-body { max-height: min(60vh, 28rem); overflow-y: auto; }
    `,
  ],
  template: `
    <div class="card mb-3">
      <div class="card-header d-flex align-items-center flex-wrap gap-2">
        <h3 class="card-title mb-0"><i class="ti ti-mail-forward me-2" aria-hidden="true"></i>Admin report emails</h3>
        <button type="button" class="btn btn-primary btn-sm ms-auto" (click)="openSend()" [disabled]="loading() || loadFailed()">
          <i class="ti ti-send me-1" aria-hidden="true"></i>Send now
        </button>
      </div>
      <div class="card-body">
        <p class="text-secondary small mb-3">
          Every morning the report about the previous day goes to the people marked
          <span class="badge tone-green">Gets the report</span> below. Active admin users are listed
          automatically. Add anyone else with their email address: it is saved and used for every later
          report, until you pause or remove it.
        </p>

        @if (loading()) {
          <div class="text-secondary small"><span class="spinner-border spinner-border-sm me-2"></span>Loading…</div>
        } @else if (loadFailed()) {
          <div class="text-danger small">
            Could not load the recipient list.
            <button type="button" class="btn btn-sm btn-outline-danger ms-2" (click)="load()">Retry</button>
          </div>
        } @else {
          @if (!overview()!.scheduledSendEnabled) {
            <div class="alert alert-warning py-2 small" role="status">
              The automatic morning send is switched off on the server. "Send now" still works.
            </div>
          }
          @if (missingEmailCount() > 0) {
            <div class="alert alert-warning py-2 small" role="status">
              <strong>{{ missingEmailCount() }} admin{{ missingEmailCount() === 1 ? '' : 's' }}</strong>
              {{ missingEmailCount() === 1 ? 'has' : 'have' }} no email on their profile, so
              {{ missingEmailCount() === 1 ? 'is' : 'are' }} not getting the report. Use
              <strong>Add email</strong> next to their name to include them.
            </div>
          }

          <div role="list" aria-label="Report recipients">
            @for (row of overview()!.rows; track rowKey(row)) {
              <div class="rr-row" role="listitem">
                <span class="rr-icon" aria-hidden="true">
                  <i class="ti" [class.ti-user-shield]="row.kind === 'ADMIN_USER'" [class.ti-mail-plus]="row.kind === 'ADDED'"
                     [class.ti-server]="row.kind === 'SERVER'"></i>
                </span>
                <div class="rr-main">
                  <div class="rr-name">
                    {{ row.name }}
                    <span class="badge tone-blue ms-1">{{ kindLabel(row) }}</span>
                  </div>
                  @if (row.email) {
                    <div class="rr-email text-secondary small">{{ row.email }}</div>
                  } @else {
                    <div class="small text-danger">No email on the profile</div>
                  }
                  @if (row.note) {
                    <div class="text-secondary small mt-1"><i class="ti ti-info-circle me-1" aria-hidden="true"></i>{{ row.note }}</div>
                  }
                </div>
                <div class="rr-actions">
                  @if (row.scheduled) {
                    <span class="badge tone-green">Gets the report</span>
                  } @else if (row.kind === 'ADDED' && !row.active) {
                    <span class="badge tone-grey">Paused</span>
                  } @else if (!row.email) {
                    <span class="badge tone-amber">No email</span>
                  } @else {
                    <span class="badge tone-grey">Not used</span>
                  }
                  @if (row.kind === 'ADMIN_USER' && !row.email) {
                    <button type="button" class="btn btn-sm btn-outline-primary" (click)="addEmailFor(row)">Add email</button>
                  }
                  @if (row.kind === 'ADDED' && row.extraId !== null) {
                    <button type="button" class="btn btn-sm btn-ghost-secondary" [disabled]="isRowBusy(row)"
                            (click)="toggleActive(row)" [attr.aria-label]="(row.active ? 'Pause ' : 'Resume ') + row.name"
                            [title]="row.active ? 'Pause' : 'Resume'">
                      <i class="ti" [class.ti-player-pause]="row.active" [class.ti-player-play]="!row.active" aria-hidden="true"></i>
                    </button>
                    <button type="button" class="btn btn-sm btn-ghost-danger" [disabled]="isRowBusy(row)"
                            (click)="remove(row)" [attr.aria-label]="'Remove ' + row.name" title="Remove">
                      <i class="ti ti-trash" aria-hidden="true"></i>
                    </button>
                  }
                </div>
              </div>
            }
          </div>

          <form [formGroup]="addForm" (ngSubmit)="add()" class="mt-3" novalidate>
            <div class="fw-semibold small mb-2">Add another address</div>
            <div class="row g-2 align-items-start">
              <div class="col-12 col-md-4">
                <input class="form-control" formControlName="name" maxlength="100" placeholder="Name (optional)"
                       aria-label="Name (optional)" autocomplete="off" />
              </div>
              <div class="col-12 col-md-5">
                <input #emailInput class="form-control" type="email" formControlName="email" maxlength="255"
                       placeholder="name@company.com" aria-label="Email address" autocomplete="off" />
              </div>
              <div class="col-12 col-md-3 d-grid">
                <button type="submit" class="btn btn-outline-primary" [disabled]="adding()">
                  @if (adding()) { <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span> }
                  <i class="ti ti-plus me-1" aria-hidden="true"></i>Add
                </button>
              </div>
            </div>
            @if (addError(); as err) {
              <div class="text-danger small mt-2" role="alert">{{ err }}</div>
            }
          </form>
        }
      </div>
    </div>

    @if (sendOpen()) {
      <div class="shifa-overlay" (click)="closeSend()"></div>
      <div class="shifa-modal" adminDialog aria-labelledby="send-report-title" (dialogClose)="closeSend()">
        <div class="card">
          <div class="card-header">
            <h3 class="card-title mb-0" id="send-report-title"><i class="ti ti-send me-2" aria-hidden="true"></i>Send the morning report now</h3>
            <button type="button" class="btn-close ms-auto" aria-label="Close" (click)="closeSend()"></button>
          </div>

          @if (sendResult(); as res) {
            <div class="card-body">
              <div class="alert mb-3" [class.alert-success]="res.failures.length === 0" [class.alert-warning]="res.failures.length > 0" role="status">
                <strong>Sent to {{ res.sent }} of {{ res.requested }}.</strong>
                The report is for {{ res.date }}: {{ res.orderCount }} order{{ res.orderCount === 1 ? '' : 's' }}.
              </div>
              @if (res.failures.length > 0) {
                <div class="fw-semibold small mb-1 text-danger">Could not be sent to</div>
                <ul class="small mb-0">
                  @for (f of res.failures; track f.email) {
                    <li><strong>{{ f.email }}</strong>: {{ f.reason }}</li>
                  }
                </ul>
              }
            </div>
            <div class="card-footer d-flex justify-content-end gap-2">
              <button type="button" class="btn btn-primary" data-autofocus (click)="closeSend()">Done</button>
            </div>
          } @else {
            <div class="card-body rr-modal-body">
              <label class="form-label small fw-semibold" for="send-date">Report for</label>
              <input id="send-date" type="date" class="form-control mb-1" [value]="sendDate()" [max]="today"
                     (input)="onDate($event)" />
              <div class="form-hint small text-secondary mb-3">Yesterday is the usual choice, the same as the 8 AM email.</div>

              <div class="d-flex align-items-center gap-2 mb-1">
                <span class="fw-semibold small">Send to</span>
                <span class="text-secondary small">{{ selectedCount() }} of {{ sendableRows().length }} selected</span>
                <span class="ms-auto d-flex gap-2">
                  <button type="button" class="btn btn-link btn-sm p-0" (click)="selectAll()">Select all</button>
                  <button type="button" class="btn btn-link btn-sm p-0" (click)="clearSelection()">Clear</button>
                </span>
              </div>

              <div role="group" aria-label="Choose recipients">
                @for (row of pickRows(); track rowKey(row)) {
                  <label class="rr-pick" [class.is-off]="!row.sendable">
                    <input type="checkbox" class="form-check-input" [checked]="isSelected(row)" [disabled]="!row.sendable || sending()"
                           (change)="toggle(row)" />
                    <span class="rr-main">
                      <span class="rr-name d-block">{{ row.name }} <span class="badge tone-blue ms-1">{{ kindLabel(row) }}</span></span>
                      @if (row.email) {
                        <span class="rr-email text-secondary small d-block">{{ row.email }}</span>
                      } @else {
                        <span class="small text-danger d-block">No email on the profile. Add one in the list first.</span>
                      }
                      @if (row.email && !row.sendable && row.note) {
                        <span class="text-secondary small d-block">{{ row.note }}</span>
                      }
                    </span>
                  </label>
                }
              </div>

              <form [formGroup]="quickForm" (ngSubmit)="quickAdd()" class="mt-3" novalidate>
                <div class="fw-semibold small mb-2">Add another address (it is saved for later reports too)</div>
                <div class="row g-2">
                  <div class="col-12 col-sm-4">
                    <input class="form-control form-control-sm" formControlName="name" maxlength="100" placeholder="Name (optional)"
                           aria-label="Name (optional)" autocomplete="off" />
                  </div>
                  <div class="col-12 col-sm-5">
                    <input class="form-control form-control-sm" type="email" formControlName="email" maxlength="255"
                           placeholder="name@company.com" aria-label="Email address" autocomplete="off" />
                  </div>
                  <div class="col-12 col-sm-3 d-grid">
                    <button type="submit" class="btn btn-sm btn-outline-primary" [disabled]="quickAdding() || sending()">Add and select</button>
                  </div>
                </div>
                @if (quickError(); as err) {
                  <div class="text-danger small mt-2" role="alert">{{ err }}</div>
                }
              </form>
            </div>
            <div class="card-footer d-flex justify-content-end gap-2">
              <button type="button" class="btn btn-outline-secondary" (click)="closeSend()" [disabled]="sending()">Cancel</button>
              <button type="button" class="btn btn-primary" (click)="send()"
                      [disabled]="sending() || selectedCount() === 0 || !dateOk()">
                @if (sending()) { <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span> }
                <i class="ti ti-send me-1" aria-hidden="true"></i>Send to {{ selectedCount() }}
              </button>
            </div>
          }
        </div>
      </div>
    }
  `,
})
export class ReportRecipientsManagerComponent implements OnInit {
  private readonly service = inject(ReportRecipientsService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  private readonly emailInput = viewChild<ElementRef<HTMLInputElement>>('emailInput');

  protected readonly overview = signal<ReportRecipientOverview | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);

  protected readonly addForm = this.fb.nonNullable.group({ name: [''], email: [''] });
  protected readonly adding = signal(false);
  protected readonly addError = signal<string | null>(null);
  private readonly busyRows = signal<ReadonlySet<number>>(new Set());

  /** Today in India (yyyy-MM-dd): the latest day the report can be sent for. */
  protected readonly today = indiaDate(0);

  protected readonly sendOpen = signal(false);
  protected readonly sendDate = signal(indiaDate(-1));
  protected readonly selected = signal<ReadonlySet<string>>(new Set());
  protected readonly sending = signal(false);
  protected readonly sendResult = signal<ReportSendResult | null>(null);
  protected readonly quickForm = this.fb.nonNullable.group({ name: [''], email: [''] });
  protected readonly quickAdding = signal(false);
  protected readonly quickError = signal<string | null>(null);

  protected readonly sendableRows = computed(() => (this.overview()?.rows ?? []).filter((r) => r.sendable && !!r.email));
  /** Rows shown in the send dialog: people who can be picked first, then the ones who cannot (and why). */
  protected readonly pickRows = computed(() => {
    const rows = (this.overview()?.rows ?? []).filter((r) => r.kind !== 'ADDED' || r.active);
    return [...rows.filter((r) => r.sendable), ...rows.filter((r) => !r.sendable)];
  });
  protected readonly selectedCount = computed(() => this.sendableRows().filter((r) => this.selected().has(this.emailKey(r))).length);
  protected readonly missingEmailCount = computed(
    () => (this.overview()?.rows ?? []).filter((r) => r.kind === 'ADMIN_USER' && !r.email).length,
  );

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.service.list().subscribe({
      next: (ov) => {
        this.overview.set(ov);
        this.loading.set(false);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loading.set(false);
      },
    });
  }

  protected rowKey(row: ReportRecipientRow): string {
    return `${row.kind}:${row.userId ?? row.extraId ?? row.email ?? row.name}`;
  }

  protected kindLabel(row: ReportRecipientRow): string {
    return row.kind === 'ADMIN_USER' ? 'Admin user' : row.kind === 'ADDED' ? 'Added here' : 'Set on server';
  }

  protected isRowBusy(row: ReportRecipientRow): boolean {
    return row.extraId !== null && this.busyRows().has(row.extraId);
  }

  // --- Managing the list -----------------------------------------------------------------------

  /** An admin with no profile email: prefill the name and move to the email box. */
  protected addEmailFor(row: ReportRecipientRow): void {
    this.addForm.patchValue({ name: row.name, email: '' });
    this.addError.set(null);
    const input = this.emailInput()?.nativeElement;
    input?.scrollIntoView({ block: 'center', behavior: 'smooth' });
    input?.focus({ preventScroll: true });
  }

  protected add(): void {
    const { email, name } = this.addForm.getRawValue();
    if (!email.trim()) {
      this.addError.set('Enter an email address.');
      return;
    }
    this.adding.set(true);
    this.addError.set(null);
    this.service.add(email.trim(), name.trim()).subscribe({
      next: (ov) => {
        this.overview.set(ov);
        this.addForm.reset({ name: '', email: '' });
        this.adding.set(false);
        this.toasts.success('Added. They will get every report from now on.');
      },
      error: (err: HttpErrorResponse) => {
        this.adding.set(false);
        this.addError.set(this.errorMessage(err, 'Could not add the address.'));
      },
    });
  }

  protected toggleActive(row: ReportRecipientRow): void {
    if (row.extraId === null) {
      return;
    }
    const id = row.extraId;
    this.markBusy(id, true);
    this.service.setActive(id, !row.active).subscribe({
      next: (ov) => {
        this.overview.set(ov);
        this.markBusy(id, false);
        this.toasts.success(row.active ? `${row.name} paused.` : `${row.name} will get reports again.`);
      },
      error: (err: HttpErrorResponse) => {
        this.markBusy(id, false);
        this.toasts.error(this.errorMessage(err, 'Could not change this recipient.'));
      },
    });
  }

  protected async remove(row: ReportRecipientRow): Promise<void> {
    if (row.extraId === null) {
      return;
    }
    const id = row.extraId;
    const ok = await this.confirm.confirm({
      title: 'Remove this recipient?',
      message: `${row.name} (${row.email}) will stop getting the admin reports. You can add them again later.`,
      confirmLabel: 'Remove',
      danger: true,
      icon: 'ti-trash',
    });
    if (!ok) {
      return;
    }
    this.markBusy(id, true);
    this.service.remove(id).subscribe({
      next: (ov) => {
        this.overview.set(ov);
        this.markBusy(id, false);
        this.toasts.success('Removed.');
      },
      error: (err: HttpErrorResponse) => {
        this.markBusy(id, false);
        this.toasts.error(this.errorMessage(err, 'Could not remove this recipient.'));
      },
    });
  }

  private markBusy(id: number, busy: boolean): void {
    this.busyRows.update((current) => {
      const next = new Set(current);
      if (busy) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  // --- Send now --------------------------------------------------------------------------------

  protected openSend(): void {
    this.sendResult.set(null);
    this.quickError.set(null);
    this.sendDate.set(indiaDate(-1));
    // Everyone who can receive it starts ticked; untick whoever should be left out.
    this.selected.set(new Set(this.sendableRows().map((r) => this.emailKey(r))));
    this.sendOpen.set(true);
  }

  protected closeSend(): void {
    if (this.sending()) {
      return;
    }
    this.sendOpen.set(false);
  }

  protected onDate(event: Event): void {
    this.sendDate.set((event.target as HTMLInputElement).value);
  }

  protected dateOk(): boolean {
    const d = this.sendDate();
    return /^\d{4}-\d{2}-\d{2}$/.test(d) && d <= this.today;
  }

  protected isSelected(row: ReportRecipientRow): boolean {
    return row.sendable && this.selected().has(this.emailKey(row));
  }

  protected toggle(row: ReportRecipientRow): void {
    if (!row.sendable || !row.email) {
      return;
    }
    const key = this.emailKey(row);
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else {
        next.add(key);
      }
      return next;
    });
  }

  protected selectAll(): void {
    this.selected.set(new Set(this.sendableRows().map((r) => this.emailKey(r))));
  }

  protected clearSelection(): void {
    this.selected.set(new Set());
  }

  /** Adds an address from inside the dialog, keeps it for later reports, and ticks it. */
  protected quickAdd(): void {
    const { email, name } = this.quickForm.getRawValue();
    if (!email.trim()) {
      this.quickError.set('Enter an email address.');
      return;
    }
    this.quickAdding.set(true);
    this.quickError.set(null);
    this.service.add(email.trim(), name.trim()).subscribe({
      next: (ov) => {
        this.overview.set(ov);
        const added = ov.rows.find((r) => r.email?.toLowerCase() === email.trim().toLowerCase());
        if (added && added.sendable) {
          this.selected.update((current) => new Set(current).add(this.emailKey(added)));
        }
        this.quickForm.reset({ name: '', email: '' });
        this.quickAdding.set(false);
        this.toasts.success('Added and selected. It will get later reports too.');
      },
      error: (err: HttpErrorResponse) => {
        this.quickAdding.set(false);
        this.quickError.set(this.errorMessage(err, 'Could not add the address.'));
      },
    });
  }

  protected send(): void {
    const emails = this.sendableRows()
      .filter((r) => this.selected().has(this.emailKey(r)))
      .map((r) => r.email as string);
    if (emails.length === 0 || !this.dateOk() || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.service.send(this.sendDate(), emails).subscribe({
      next: (result) => {
        this.sending.set(false);
        this.sendResult.set(result);
        if (result.failures.length === 0) {
          this.toasts.success(`Report sent to ${result.sent}.`);
        } else {
          this.toasts.error(`Sent to ${result.sent} of ${result.requested}. ${result.failures.length} failed.`);
        }
      },
      error: (err: HttpErrorResponse) => {
        this.sending.set(false);
        this.toasts.error(this.errorMessage(err, 'Could not send the report. Please try again.'));
      },
    });
  }

  private emailKey(row: ReportRecipientRow): string {
    return (row.email ?? '').trim().toLowerCase();
  }

  private errorMessage(err: HttpErrorResponse, fallback: string): string {
    return (err.error as { message?: string } | undefined)?.message ?? fallback;
  }
}
