import { Component, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { FinanceService } from './finance.service';
import { ProfitLossResponse } from './finance.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';

/**
 * Admin Profit &amp; Loss report (Phase C3 — ADMIN + ACCOUNTANT).
 *
 * <p>A from/to date-range picker (defaulting to the current month) driving the
 * {@code GET /api/admin/finance/pnl} summary. Headline revenue, courier cost
 * and total expenses feed a prominent, colour-coded net-profit card; claims and
 * COD figures are shown as secondary metrics with an expense-by-category
 * breakdown table.
 */
@Component({
  selector: 'admin-profit-loss',
  imports: [ReactiveFormsModule, PageHeaderComponent, StatePanelComponent],
  templateUrl: './profit-loss.component.html',
  styleUrl: './profit-loss.component.css',
})
export class ProfitLossComponent implements OnInit {
  private readonly service = inject(FinanceService);

  protected readonly report = signal<ProfitLossResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly loadError = signal<string | null>(null);

  protected readonly form = new FormGroup({
    from: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
    to: new FormControl<string>('', { nonNullable: true, validators: [Validators.required] }),
  });

  ngOnInit(): void {
    const { from, to } = this.currentMonthRange();
    this.form.setValue({ from, to });
    this.generate();
  }

  generate(): void {
    if (this.loading()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { from, to } = this.form.getRawValue();
    if (from > to) {
      this.loadError.set('The "from" date must be on or before the "to" date.');
      return;
    }
    this.loading.set(true);
    this.loadError.set(null);
    this.service.profitLoss(from, to).subscribe({
      next: (res) => {
        this.report.set(res);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load the profit & loss report. Please try again.');
        this.loading.set(false);
      },
    });
  }

  money(value: number | null | undefined): string {
    const n = typeof value === 'number' && Number.isFinite(value) ? value : 0;
    const sign = n < 0 ? '-' : '';
    return `${sign}₹${Math.abs(n).toFixed(2)}`;
  }

  private currentMonthRange(): { from: string; to: string } {
    const now = new Date();
    const first = new Date(now.getFullYear(), now.getMonth(), 1);
    const last = new Date(now.getFullYear(), now.getMonth() + 1, 0);
    return { from: this.iso(first), to: this.iso(last) };
  }

  private iso(d: Date): string {
    const pad = (n: number) => n.toString().padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }
}
