import { Component, OnInit, inject, signal } from '@angular/core';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { SettingsService } from './settings.service';

/**
 * Settings → Automation: the switch for status emails sent to CUSTOMERS (order approved,
 * dispatched, delivered). It is off until an admin turns it on. It saves on its own, outside
 * the main settings form, and does not affect the emails sent to staff (the daily report to the
 * admins and the temporary-password email).
 */
@Component({
  selector: 'admin-customer-email-switch',
  standalone: true,
  template: `
    <div class="card mb-3">
      <div class="card-header">
        <h3 class="card-title mb-0"><i class="ti ti-mail me-2" aria-hidden="true"></i>Emails to customers</h3>
      </div>
      <div class="card-body">
        <p class="text-secondary small mb-3">
          <i class="ti ti-info-circle me-1" aria-hidden="true"></i>
          When on, a customer whose order has an email address gets an email when the order is
          <strong>approved</strong>, <strong>dispatched</strong> and <strong>delivered</strong>.
          When off, <strong>no email is sent to any customer</strong>. Emails to staff, such as the
          daily report to the admins and the temporary-password email, are not affected.
        </p>
        @if (loading()) {
          <div class="text-secondary small"><span class="spinner-border spinner-border-sm me-2"></span>Loading…</div>
        } @else if (loadFailed()) {
          <div class="text-danger small">
            Could not load this setting.
            <button type="button" class="btn btn-sm btn-outline-danger ms-2" (click)="load()">Retry</button>
          </div>
        } @else {
          <label class="form-check form-switch mb-0">
            <input
              class="form-check-input"
              type="checkbox"
              [checked]="enabled()"
              [disabled]="saving()"
              (click)="onClick($event)"
              aria-label="Send status emails to customers"
            />
            <span class="form-check-label">
              {{ enabled() ? 'Customer emails are ON' : 'Customer emails are OFF' }}
              @if (saving()) {
                <span class="spinner-border spinner-border-sm ms-2" aria-hidden="true"></span>
              }
            </span>
          </label>
        }
      </div>
    </div>
  `,
})
export class CustomerEmailSwitchComponent implements OnInit {
  private readonly service = inject(SettingsService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly enabled = signal(false);
  protected readonly loading = signal(true);
  protected readonly loadFailed = signal(false);
  protected readonly saving = signal(false);

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.service.customerEmailsEnabled().subscribe({
      next: (r) => {
        this.enabled.set(r.enabled);
        this.loading.set(false);
      },
      error: () => {
        this.loadFailed.set(true);
        this.loading.set(false);
      },
    });
  }

  /**
   * The checkbox never flips by itself: the click is cancelled and the saved value drives what is
   * shown, so a cancelled confirmation or a failed save leaves the switch exactly where it was.
   */
  protected onClick(event: Event): void {
    event.preventDefault();
    void this.change(!this.enabled());
  }

  /** Turning it on asks first; turning it off applies straight away. */
  private async change(wanted: boolean): Promise<void> {
    if (this.saving()) {
      return;
    }
    if (wanted) {
      const ok = await this.confirm.confirm({
        title: 'Start emailing customers?',
        message:
          'Customers with an email address on their order will get an email when the order is approved, dispatched and delivered.',
        confirmLabel: 'Turn on',
        icon: 'ti-mail',
      });
      if (!ok) {
        return;
      }
    }
    this.saving.set(true);
    this.service.setCustomerEmailsEnabled(wanted).subscribe({
      next: (r) => {
        this.enabled.set(r.enabled);
        this.saving.set(false);
        this.toasts.success(r.enabled ? 'Customer emails are on.' : 'Customer emails are off.');
      },
      error: () => {
        this.saving.set(false);
        this.toasts.error('Could not change the setting. Please try again.');
      },
    });
  }
}
