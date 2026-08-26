import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError, AuthService, Role } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { LedgerService } from './ledger.service';
import {
  DrCr,
  LedgerAccount,
  PostVoucherRequest,
  Voucher,
  VOUCHER_TYPES,
  VoucherType,
  drcrLabel,
  voucherTypeLabel,
} from './ledger.model';
import { BalanceLine, balanceOf } from './voucher-balance.util';

/** Today as an ISO `yyyy-MM-dd` string for the default voucher date. */
function todayIso(): string {
  const now = new Date();
  const off = now.getTimezoneOffset();
  return new Date(now.getTime() - off * 60_000).toISOString().slice(0, 10);
}

/**
 * Manual double-entry voucher entry (Reqs 5.1, 5.2, 5.7, 6.3, 7.1) — the admin
 * UI for {@code POST /api/accounting/vouchers} and
 * {@code POST /api/accounting/vouchers/{id}/reverse}.
 *
 * <p>A reactive form captures the voucher type (one of the eight supported types,
 * Req 7.1), date, and narration (all required, Req 5.7), plus a dynamic list of
 * at least two Dr/Cr lines (Req 5.1) — each a ledger account, a side, and a
 * strictly-positive amount. A <strong>live Dr/Cr balance indicator</strong>
 * (driven by the pure {@link balanceOf} helper) shows the running debit/credit
 * totals and their difference, and the <strong>Post</strong> button stays
 * disabled until the form is valid and the debits equal the credits (Req 5.2),
 * mirroring the server's double-entry rule exactly.
 *
 * <p>Posting is immutable; corrections are made by reversing. After a successful
 * post — or by looking one up by id — a posted voucher can be reversed (Req 6.3),
 * which posts a balancing reversing voucher on the server.
 *
 * <p>All entry and reversal affordances are gated to ADMIN / ACCOUNTANT; the CA
 * role is read-only server-side, so the form and reverse actions are hidden for
 * it (it sees a read-only notice instead).
 */
@Component({
  selector: 'admin-voucher-entry',
  imports: [ReactiveFormsModule, DecimalPipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './voucher-entry.component.html',
  styleUrl: './voucher-entry.component.css',
})
export class VoucherEntryComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly ledger = inject(LedgerService);
  private readonly auth = inject(AuthService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  /** Label/enum helpers exposed to the template. */
  protected readonly voucherTypes = VOUCHER_TYPES;
  protected readonly voucherTypeLabel = voucherTypeLabel;
  protected readonly drcrLabel = drcrLabel;

  /**
   * Posting and reversing are restricted to ADMIN / ACCOUNTANT (CA is read-only
   * server-side, so the whole entry/reverse UI is hidden for it).
   */
  protected readonly canPost = computed(() => this.auth.hasAnyRole(Role.ADMIN, Role.ACCOUNTANT));

  // --- Ledger accounts (line pickers) -------------------------------------
  protected readonly ledgers = signal<LedgerAccount[]>([]);
  protected readonly ledgersLoading = signal(true);
  protected readonly ledgersError = signal<string | null>(null);

  // --- Submit / feedback state --------------------------------------------
  protected readonly submitting = signal(false);
  protected readonly submitAttempted = signal(false);
  protected readonly serverError = signal<string | null>(null);

  /**
   * The most recently posted (or looked-up) voucher, shown in a panel with a
   * Reverse action (Req 6.3). Null hides the panel.
   */
  protected readonly postedVoucher = signal<Voucher | null>(null);
  protected readonly reversing = signal(false);

  // --- Reverse-by-id lookup -----------------------------------------------
  protected readonly lookupId = signal<number | null>(null);
  protected readonly lookupLoading = signal(false);
  protected readonly lookupError = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    type: ['JOURNAL' as VoucherType, [Validators.required]],
    date: [todayIso(), [Validators.required]],
    narration: ['', [Validators.required, Validators.maxLength(500)]],
    lines: this.fb.array([this.newLine(), this.newLine('CREDIT')]),
  });

  /** A snapshot of the line sides/amounts, refreshed on every change. */
  private readonly model = signal<BalanceLine[]>([]);

  /** The live Dr/Cr balance from the pure helper — drives the indicator + gating. */
  protected readonly balance = computed(() => balanceOf(this.model()));

  /**
   * Whether the voucher may be posted: the reactive form is valid AND the debits
   * equal the credits (Req 5.2). The Post button binds its `disabled` to the
   * negation of this.
   */
  protected readonly canSubmit = computed(
    () => this.form.valid && this.balance().balanced && !this.submitting(),
  );

  ngOnInit(): void {
    this.loadLedgers();
    this.model.set(this.snapshotLines());
    this.form.valueChanges.subscribe(() => this.model.set(this.snapshotLines()));
  }

  /** Loads the postable ledger accounts for the line pickers. */
  loadLedgers(): void {
    this.ledgersLoading.set(true);
    this.ledgersError.set(null);
    this.ledger.listLedgers().subscribe({
      next: (rows) => {
        this.ledgers.set(rows);
        this.ledgersLoading.set(false);
      },
      error: () => {
        this.ledgersError.set('Could not load ledger accounts. Please try again.');
        this.ledgersLoading.set(false);
      },
    });
  }

  // --- Line FormArray management ------------------------------------------

  get lines(): FormArray<FormGroup> {
    return this.form.controls.lines as FormArray<FormGroup>;
  }

  private newLine(side: DrCr = 'DEBIT'): FormGroup {
    return this.fb.nonNullable.group({
      ledgerAccountId: [null as number | null, [Validators.required]],
      side: [side as DrCr, [Validators.required]],
      amount: [null as number | null, [Validators.required, Validators.min(0.01)]],
    });
  }

  addLine(): void {
    this.lines.push(this.newLine());
  }

  /** Removes a line, keeping the minimum of two required for a double entry (Req 5.1). */
  removeLine(index: number): void {
    if (this.lines.length > 2) {
      this.lines.removeAt(index);
    }
  }

  /** Flips a line between the debit and credit side. */
  toggleSide(index: number): void {
    const control = this.lines.at(index).controls['side'];
    control.setValue(control.value === 'DEBIT' ? 'CREDIT' : 'DEBIT');
  }

  /** True when a line control is invalid and the user has interacted / attempted submit. */
  lineInvalid(index: number, name: 'ledgerAccountId' | 'amount'): boolean {
    const control = this.lines.at(index).controls[name];
    return control.invalid && (control.touched || this.submitAttempted());
  }

  /** True when a top-level control is invalid and touched / after a submit attempt. */
  invalid(name: 'type' | 'date' | 'narration'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || this.submitAttempted());
  }

  // --- Posting ------------------------------------------------------------

  /**
   * Posts the voucher (Req 5). Guarded by {@link canSubmit} — the server also
   * re-validates the double-entry rule and returns the exact totals on imbalance
   * (Req 5.3), which are surfaced inline.
   */
  submit(): void {
    this.submitAttempted.set(true);
    this.serverError.set(null);
    if (!this.canPost()) {
      return;
    }
    this.form.markAllAsTouched();
    if (!this.canSubmit()) {
      return;
    }

    const raw = this.form.getRawValue();
    const request: PostVoucherRequest = {
      type: raw.type,
      date: raw.date,
      narration: raw.narration.trim(),
      lines: this.lines.controls.map((group) => {
        const g = group.getRawValue() as { ledgerAccountId: number; side: DrCr; amount: number };
        return {
          ledgerAccountId: Number(g.ledgerAccountId),
          side: g.side,
          amount: Number(g.amount),
        };
      }),
    };

    this.submitting.set(true);
    this.ledger.postVoucher(request).subscribe({
      next: (voucher) => {
        this.submitting.set(false);
        this.toasts.success(`Voucher ${voucher.reference} posted.`);
        this.postedVoucher.set(voucher);
        this.resetForm();
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        const message = this.messageOf(err) ?? 'Could not post the voucher. Please try again.';
        this.serverError.set(message);
        this.toasts.error(message);
      },
    });
  }

  /** Resets the form to a blank two-line draft (after a successful post). */
  private resetForm(): void {
    this.submitAttempted.set(false);
    this.serverError.set(null);
    this.lines.clear();
    this.lines.push(this.newLine('DEBIT'));
    this.lines.push(this.newLine('CREDIT'));
    this.form.reset({
      type: 'JOURNAL',
      date: todayIso(),
      narration: '',
    });
    this.model.set(this.snapshotLines());
  }

  // --- Reversal (Req 6.3) --------------------------------------------------

  /**
   * Reverses the posted voucher currently shown in the panel (Req 6.3): posts a
   * balancing reversing voucher whose lines negate the original line by line.
   * ADMIN / ACCOUNTANT only.
   */
  async reverse(): Promise<void> {
    const voucher = this.postedVoucher();
    if (!voucher || !this.canPost() || this.reversing()) {
      return;
    }
    if (voucher.reversedByVoucherId) {
      this.toasts.info('This voucher has already been reversed.');
      return;
    }
    const ok = await this.confirm.confirm({
      title: 'Reverse voucher',
      message: `Post a reversing entry that negates voucher ${voucher.reference}? The original stays on record.`,
      confirmLabel: 'Reverse',
      cancelLabel: 'Cancel',
      danger: true,
      icon: 'ti-arrow-back-up',
    });
    if (!ok) {
      return;
    }

    this.reversing.set(true);
    this.ledger.reverseVoucher(voucher.id).subscribe({
      next: (reversing) => {
        this.reversing.set(false);
        this.toasts.success(`Reversing voucher ${reversing.reference} posted.`);
        // Show the newly posted reversing voucher in the panel.
        this.postedVoucher.set(reversing);
      },
      error: (err: HttpErrorResponse) => {
        this.reversing.set(false);
        const message = this.messageOf(err) ?? 'Could not reverse the voucher. Please try again.';
        this.toasts.error(message);
      },
    });
  }

  /** Dismisses the posted-voucher panel. */
  dismissPosted(): void {
    this.postedVoucher.set(null);
  }

  // --- Reverse-by-id lookup -----------------------------------------------

  /** Loads a posted voucher by id into the panel so it can be reviewed / reversed. */
  lookupVoucher(): void {
    const id = this.lookupId();
    this.lookupError.set(null);
    if (id == null || !Number.isFinite(id) || id <= 0) {
      this.lookupError.set('Enter a valid voucher id.');
      return;
    }
    this.lookupLoading.set(true);
    this.ledger.getVoucher(id).subscribe({
      next: (voucher) => {
        this.lookupLoading.set(false);
        this.postedVoucher.set(voucher);
      },
      error: (err: HttpErrorResponse) => {
        this.lookupLoading.set(false);
        this.lookupError.set(this.messageOf(err) ?? `No voucher found with id ${id}.`);
      },
    });
  }

  /** Resolves a ledger account's name for display in the posted-voucher lines. */
  ledgerName(id: number): string {
    return this.ledgers().find((l) => l.id === id)?.name ?? `#${id}`;
  }

  // --- Helpers ------------------------------------------------------------

  /** Plain snapshot of each line's side + amount for the balance indicator. */
  private snapshotLines(): BalanceLine[] {
    return this.lines.controls.map((group) => {
      const g = group.getRawValue() as { side: DrCr; amount: number | null };
      return { side: g.side, amount: g.amount };
    });
  }

  private messageOf(err: HttpErrorResponse): string | null {
    const body = err.error as ApiError | undefined;
    return body?.message ?? null;
  }
}
