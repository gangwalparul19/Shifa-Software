import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { BankAccount, BankAccountRequest, BankAccountType, SettingsService } from './settings.service';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

const ACCOUNT_TYPES: { value: BankAccountType; label: string }[] = [
  { value: 'UPI_QR', label: 'UPI / QR account' },
  { value: 'BANK_TRANSFER', label: 'Bank transfer account' },
  { value: 'SHOPIFY_SETTLEMENT', label: 'Shopify settlement' },
  { value: 'QUICSHIP_REMITTANCE', label: 'QuikShip remittance' },
  { value: 'CASH', label: 'Cash / counter' },
  { value: 'OTHER', label: 'Other' },
];

@Component({
  selector: 'admin-bank-account-manager',
  standalone: true,
  imports: [ReactiveFormsModule],
  templateUrl: './bank-account-manager.component.html',
  styleUrl: './bank-account-manager.component.css',
})
export class BankAccountManagerComponent implements OnInit {
  private readonly service = inject(SettingsService);
  private readonly fb = inject(FormBuilder);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly accountTypes = ACCOUNT_TYPES;
  protected readonly accounts = signal<BankAccount[]>([]);
  protected readonly loading = signal(true);
  protected readonly busy = signal(false);
  protected readonly editingId = signal<number | null>(null);
  protected readonly form = this.fb.nonNullable.group({
    displayName: ['', [Validators.required, Validators.maxLength(100)]],
    accountType: ['UPI_QR' as BankAccountType, [Validators.required]],
    bankName: ['', [Validators.maxLength(120)]],
    accountHolderName: ['', [Validators.maxLength(120)]],
    accountNumber: ['', [Validators.maxLength(40)]],
    ifsc: ['', [Validators.maxLength(20)]],
    branch: ['', [Validators.maxLength(120)]],
    upiId: ['', [Validators.maxLength(120)]],
    active: [true],
    defaultAccount: [false],
    sortOrder: [0, [Validators.min(0)]],
    internalNote: ['', [Validators.maxLength(500)]],
  });

  ngOnInit(): void { this.load(); }

  protected load(): void {
    this.loading.set(true);
    this.service.bankAccounts().subscribe({
      next: (rows) => { this.accounts.set(rows); this.loading.set(false); },
      error: () => { this.loading.set(false); this.toasts.error('Could not load bank accounts.'); },
    });
  }

  protected openCreate(): void {
    this.editingId.set(null);
    this.form.reset({ displayName: '', accountType: 'UPI_QR', bankName: '', accountHolderName: '',
      accountNumber: '', ifsc: '', branch: '', upiId: '', active: true, defaultAccount: false,
      sortOrder: 0, internalNote: '' });
    this.form.markAsDirty();
  }

  protected openEdit(account: BankAccount): void {
    this.editingId.set(account.id);
    this.form.reset({
      displayName: account.displayName,
      accountType: account.accountType,
      bankName: account.bankName ?? '',
      accountHolderName: account.accountHolderName ?? '',
      accountNumber: account.accountNumber ?? '',
      ifsc: account.ifsc ?? '',
      branch: account.branch ?? '',
      upiId: account.upiId ?? '',
      active: account.active,
      defaultAccount: account.defaultAccount,
      sortOrder: account.sortOrder,
      internalNote: account.internalNote ?? '',
    });
  }

  protected cancelEdit(): void { this.editingId.set(null); this.form.reset(); }

  protected save(): void {
    if (this.busy()) return;
    if (this.form.invalid) { this.form.markAllAsTouched(); return; }
    const raw = this.form.getRawValue();
    const request: BankAccountRequest = { ...raw, displayName: raw.displayName.trim(),
      bankName: raw.bankName.trim() || null, accountHolderName: raw.accountHolderName.trim() || null,
      accountNumber: raw.accountNumber.trim() || null, ifsc: raw.ifsc.trim().toUpperCase() || null,
      branch: raw.branch.trim() || null, upiId: raw.upiId.trim() || null,
      internalNote: raw.internalNote.trim() || null, ledgerAccountId: null };
    this.busy.set(true);
    const id = this.editingId();
    const call = id == null ? this.service.createBankAccount(request) : this.service.updateBankAccount(id, request);
    call.subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.accounts.update((rows) => id == null ? [...rows, saved] : rows.map((r) => r.id === saved.id ? saved : r));
        this.editingId.set(null);
        this.form.reset();
        this.toasts.success(id == null ? 'Bank account added.' : 'Bank account updated.');
      },
      error: (err: HttpErrorResponse) => { this.busy.set(false); this.toasts.error(this.errorMessage(err)); },
    });
  }

  protected async deactivate(account: BankAccount): Promise<void> {
    const confirmed = await this.confirm.confirm({
      title: 'Deactivate bank account',
      message: `Stop using ${account.displayName} for new payments? Historical payments will remain visible.`,
      confirmLabel: 'Deactivate', danger: true, icon: 'ti-building-bank',
    });
    if (!confirmed) return;
    this.service.deactivateBankAccount(account.id).subscribe({
      next: (saved) => { this.accounts.update((rows) => rows.map((r) => r.id === saved.id ? saved : r)); this.toasts.success('Bank account deactivated.'); },
      error: (err: HttpErrorResponse) => this.toasts.error(this.errorMessage(err)),
    });
  }

  protected typeLabel(type: BankAccountType): string {
    return ACCOUNT_TYPES.find((x) => x.value === type)?.label ?? type;
  }

  private errorMessage(err: HttpErrorResponse): string {
    return (err.error as { message?: string } | undefined)?.message ?? 'Could not save the bank account.';
  }
}
