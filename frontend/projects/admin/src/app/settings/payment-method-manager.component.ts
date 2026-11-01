import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  BANK_ACCOUNT_TYPE_OPTIONS,
  BankAccountType,
  PaymentMethodConfig,
  PaymentMethodRequest,
  SettingsService,
  bankAccountTypeLabel,
} from './settings.service';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

/** Form value of the "Receiving account" select when the salesperson picks the account. */
const MODE_CHOOSE = 'CHOOSE';
/** Form value when no account is involved (cash). */
const MODE_NONE = 'NONE';
/** Prefix of the form values that auto-select an account of a given purpose. */
const MODE_AUTO_PREFIX = 'AUTO:';

/**
 * Settings → Payment methods. The methods offered at order entry live in the database, so an
 * admin can add, rename, reorder, deactivate or delete them, pick the default, and link a method
 * to an account purpose (e.g. Credit Card Link → Shopify settlement account).
 */
@Component({
  selector: 'admin-payment-method-manager',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './payment-method-manager.component.html',
  styleUrl: './payment-method-manager.component.css',
})
export class PaymentMethodManagerComponent implements OnInit {
  private readonly service = inject(SettingsService);
  private readonly fb = inject(FormBuilder);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly MODE_CHOOSE = MODE_CHOOSE;
  protected readonly MODE_NONE = MODE_NONE;
  protected readonly MODE_AUTO_PREFIX = MODE_AUTO_PREFIX;
  protected readonly accountTypes = BANK_ACCOUNT_TYPE_OPTIONS;

  protected readonly methods = signal<PaymentMethodConfig[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  /** Id of the method being edited; null when the form is closed or adding a new one. */
  protected readonly editingId = signal<number | null>(null);
  /** True while the "add" form is open. */
  protected readonly creating = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    label: ['', [Validators.required, Validators.maxLength(60)]],
    accountMode: [MODE_CHOOSE],
    sortOrder: [0, [Validators.min(0)]],
    active: [true],
    defaultMethod: [false],
  });

  ngOnInit(): void {
    this.load();
  }

  protected load(): void {
    this.loading.set(true);
    this.service.paymentMethods().subscribe({
      next: (rows) => {
        this.methods.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.toasts.error('Could not load payment methods.');
      },
    });
  }

  protected formOpen(): boolean {
    return this.creating() || this.editingId() !== null;
  }

  protected openCreate(): void {
    this.creating.set(true);
    this.editingId.set(null);
    const nextSort = this.methods().reduce((max, m) => Math.max(max, m.sortOrder), 0) + 10;
    this.form.reset({ label: '', accountMode: MODE_CHOOSE, sortOrder: nextSort, active: true, defaultMethod: false });
    this.form.controls.defaultMethod.enable();
    this.form.controls.active.enable();
  }

  protected openEdit(method: PaymentMethodConfig): void {
    this.creating.set(false);
    this.editingId.set(method.id);
    this.form.reset({
      label: method.label,
      accountMode: this.modeOf(method),
      sortOrder: method.sortOrder,
      active: method.active,
      defaultMethod: method.defaultMethod,
    });
    // The default can only be moved by making another method the default, so it stays on here
    // and stays active.
    if (method.defaultMethod) {
      this.form.controls.defaultMethod.disable();
      this.form.controls.active.disable();
    } else {
      this.form.controls.defaultMethod.enable();
      this.form.controls.active.enable();
    }
  }

  protected cancel(): void {
    this.creating.set(false);
    this.editingId.set(null);
    this.form.reset();
  }

  protected save(): void {
    if (this.busy()) return;
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    const { requiresAccount, linkedAccountType } = this.fieldsOf(raw.accountMode);
    const request: PaymentMethodRequest = {
      label: raw.label.trim(),
      requiresAccount,
      linkedAccountType,
      active: raw.active,
      defaultMethod: raw.defaultMethod,
      sortOrder: Number(raw.sortOrder) || 0,
    };
    const id = this.editingId();
    this.busy.set(true);
    const call = id === null ? this.service.createPaymentMethod(request) : this.service.updatePaymentMethod(id, request);
    call.subscribe({
      next: () => {
        this.busy.set(false);
        this.cancel();
        // Reload: saving a default clears the flag on the previous one.
        this.load();
        this.toasts.success(id === null ? 'Payment method added.' : 'Payment method updated.');
      },
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.toasts.error(this.errorMessage(err, 'Could not save the payment method.'));
      },
    });
  }

  protected makeDefault(method: PaymentMethodConfig): void {
    this.service.makeDefaultPaymentMethod(method.id).subscribe({
      next: () => {
        this.load();
        this.toasts.success(`${method.label} is now the default payment method.`);
      },
      error: (err: HttpErrorResponse) => this.toasts.error(this.errorMessage(err, 'Could not change the default.')),
    });
  }

  protected async deactivate(method: PaymentMethodConfig): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Deactivate payment method',
      message: `Stop offering ${method.label} for new orders? Payments already recorded with it stay readable.`,
      confirmLabel: 'Deactivate',
      danger: true,
      icon: 'ti-credit-card',
    });
    if (!confirmed) return;
    const { requiresAccount, linkedAccountType } = this.fieldsOf(this.modeOf(method));
    this.service
      .updatePaymentMethod(method.id, {
        label: method.label,
        requiresAccount,
        linkedAccountType,
        active: false,
        defaultMethod: method.defaultMethod,
        sortOrder: method.sortOrder,
      })
      .subscribe({
        next: () => {
          this.load();
          this.toasts.success(`${method.label} deactivated.`);
        },
        error: (err: HttpErrorResponse) => this.toasts.error(this.errorMessage(err, 'Could not deactivate the method.')),
      });
  }

  protected async remove(method: PaymentMethodConfig): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Delete payment method',
      message: `Delete ${method.label}? It has not been used on any payment, so nothing else is affected.`,
      confirmLabel: 'Delete',
      danger: true,
      icon: 'ti-trash',
    });
    if (!confirmed) return;
    this.service.deletePaymentMethod(method.id).subscribe({
      next: () => {
        this.methods.update((rows) => rows.filter((row) => row.id !== method.id));
        this.toasts.success(`${method.label} deleted.`);
      },
      error: (err: HttpErrorResponse) => this.toasts.error(this.errorMessage(err, 'Could not delete the method.')),
    });
  }

  /** Short description of how the receiving account is handled, for the table. */
  protected accountSummary(method: PaymentMethodConfig): string {
    if (method.linkedAccountType) {
      return `Auto: ${bankAccountTypeLabel(method.linkedAccountType)}`;
    }
    return method.requiresAccount ? 'Salesperson chooses' : 'Not needed';
  }

  private modeOf(method: PaymentMethodConfig): string {
    if (method.linkedAccountType) {
      return MODE_AUTO_PREFIX + method.linkedAccountType;
    }
    return method.requiresAccount ? MODE_CHOOSE : MODE_NONE;
  }

  private fieldsOf(mode: string): { requiresAccount: boolean; linkedAccountType: BankAccountType | null } {
    if (mode.startsWith(MODE_AUTO_PREFIX)) {
      return { requiresAccount: true, linkedAccountType: mode.slice(MODE_AUTO_PREFIX.length) as BankAccountType };
    }
    return { requiresAccount: mode !== MODE_NONE, linkedAccountType: null };
  }

  private errorMessage(err: HttpErrorResponse, fallback: string): string {
    return (err.error as { message?: string } | undefined)?.message ?? fallback;
  }
}
