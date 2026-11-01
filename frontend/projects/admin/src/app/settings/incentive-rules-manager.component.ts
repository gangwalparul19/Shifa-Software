import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormArray, FormBuilder, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { IncentiveRuleVersion, IncentiveRules, IncentiveTier } from '../incentives/incentives.model';
import { IncentivesService } from '../incentives/incentives.service';
import { ConfirmService } from '../shared/confirm.service';
import { InrPipe, formatInr } from '../shared/inr.pipe';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { ToastService } from '../shared/toast.service';

type TierGroup = FormGroup<{ minSales: FormControl<number | null>; bonus: FormControl<number | null> }>;

/** Slabs offered to start from when no rules exist yet (the figures the business announced). */
const SUGGESTED_TIERS: IncentiveTier[] = [
  { minSales: 25000, bonus: 500 },
  { minSales: 35000, bonus: 750 },
];

/**
 * Settings → Incentives. An admin sets the daily sales slabs (for example ₹25,000 earns ₹500,
 * ₹35,000 earns ₹750) and the day they start. Saving never edits the old rules: it adds a new version,
 * so the history below shows who changed what and when, and days already worked keep the rules that
 * applied to them.
 */
@Component({
  selector: 'admin-incentive-rules-manager',
  standalone: true,
  imports: [ReactiveFormsModule, InrPipe, IstDatePipe, PaginationComponent],
  templateUrl: './incentive-rules-manager.component.html',
  styleUrl: './incentive-rules-manager.component.css',
})
export class IncentiveRulesManagerComponent implements OnInit {
  private readonly service = inject(IncentivesService);
  private readonly fb = inject(FormBuilder);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly rules = signal<IncentiveRules | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  protected readonly saving = signal(false);

  protected readonly form = this.fb.group({
    effectiveFrom: this.fb.nonNullable.control(''),
    note: this.fb.nonNullable.control(''),
    tiers: this.fb.array<TierGroup>([]),
  });

  /** The editor's slabs as they stand, for the live preview. */
  protected readonly draftTiers = signal<IncentiveTier[]>([]);

  // History paging (client-side).
  protected readonly historyPage = signal(0);
  protected readonly historySize = signal(readPageSize('incentiveRuleHistory', 5));
  protected readonly historyTotal = computed(() => this.rules()?.versions.length ?? 0);
  protected readonly historyPages = computed(() => Math.max(1, Math.ceil(this.historyTotal() / this.historySize())));
  protected readonly historyItems = computed(() => {
    const start = this.historyPage() * this.historySize();
    return (this.rules()?.versions ?? []).slice(start, start + this.historySize());
  });

  /** The chosen start day is before today, so earlier days would be recalculated. */
  protected readonly startsInPast = computed(() => {
    const today = this.rules()?.today;
    const start = this.effectiveFromValue();
    return !!today && !!start && start < today;
  });
  protected readonly effectiveFromValue = signal('');

  protected readonly formatInr = formatInr;

  ngOnInit(): void {
    this.form.controls.effectiveFrom.valueChanges.subscribe((v) => this.effectiveFromValue.set(v));
    this.load();
  }

  protected get tiers(): FormArray<TierGroup> {
    return this.form.controls.tiers;
  }

  protected load(): void {
    this.loading.set(true);
    this.failed.set(false);
    this.service.rules().subscribe({
      next: (rules) => {
        this.rules.set(rules);
        this.loading.set(false);
        this.resetEditor(rules);
      },
      error: () => {
        this.loading.set(false);
        this.failed.set(true);
      },
    });
  }

  /** Starts the editor from the rules in force today (or the suggested slabs on first use). */
  private resetEditor(rules: IncentiveRules): void {
    const base = rules.inForce ?? rules.versions[0] ?? null;
    const slabs = base ? base.tiers : SUGGESTED_TIERS;
    this.tiers.clear();
    slabs.forEach((t) => this.tiers.push(this.tierGroup(t.minSales, t.bonus)));
    this.form.patchValue({ effectiveFrom: rules.today, note: '' });
    this.effectiveFromValue.set(rules.today);
    this.syncDraft();
  }

  private tierGroup(minSales: number | null, bonus: number | null): TierGroup {
    const group: TierGroup = this.fb.group({
      minSales: this.fb.control<number | null>(minSales),
      bonus: this.fb.control<number | null>(bonus),
    });
    group.valueChanges.subscribe(() => this.syncDraft());
    return group;
  }

  protected addTier(): void {
    const sorted = this.sortedDraft();
    const last = sorted[sorted.length - 1];
    this.tiers.push(this.tierGroup(last ? last.minSales + 10000 : null, last ? last.bonus + 250 : null));
    this.syncDraft();
  }

  protected removeTier(index: number): void {
    this.tiers.removeAt(index);
    this.syncDraft();
  }

  private syncDraft(): void {
    this.draftTiers.set(this.sortedDraft());
  }

  /** Complete slabs only, lowest first. */
  private sortedDraft(): IncentiveTier[] {
    return this.tiers.controls
      .map((g) => g.getRawValue())
      .filter((v) => v.minSales != null && v.bonus != null)
      .map((v) => ({ minSales: Number(v.minSales), bonus: Number(v.bonus) }))
      .sort((a, b) => a.minSales - b.minSales);
  }

  /** The first problem with the slabs as typed, or null when they are fine. Mirrors the server rules. */
  protected readonly problem = computed<string | null>(() => {
    const rows = this.tiers.controls.map((g) => g.getRawValue());
    this.draftTiers(); // re-evaluate whenever a slab changes
    for (const r of rows) {
      if (r.minSales == null || r.bonus == null) {
        return 'Fill in the sales amount and the bonus for every slab, or remove the empty one.';
      }
      if (!(r.minSales > 0) || !(r.bonus > 0)) {
        return 'Every slab needs sales and a bonus above zero.';
      }
    }
    const sorted = this.sortedDraft();
    for (let i = 1; i < sorted.length; i++) {
      if (sorted[i].minSales === sorted[i - 1].minSales) {
        return `Two slabs start at ${formatInr(sorted[i].minSales, 0)}. Each slab needs its own sales amount.`;
      }
      if (sorted[i].bonus <= sorted[i - 1].bonus) {
        return `The ${formatInr(sorted[i].minSales, 0)} slab must pay more than the ${formatInr(sorted[i - 1].minSales, 0)} slab.`;
      }
    }
    if (!this.effectiveFromValue()) {
      return 'Choose the day the rules start.';
    }
    return null;
  });

  protected async save(): Promise<void> {
    if (this.saving() || this.problem()) {
      return;
    }
    const tiers = this.sortedDraft();
    const effectiveFrom = this.effectiveFromValue();
    const rules = this.rules();
    if (tiers.length === 0) {
      const ok = await this.confirm.confirm({
        title: 'Switch off the daily bonus?',
        message: `No slabs are set, so no daily bonus is paid from ${effectiveFrom}. The earlier rules stay in the history.`,
        confirmLabel: 'Switch off',
        danger: true,
        icon: 'ti-coin-off',
      });
      if (!ok) return;
    } else if (rules && effectiveFrom < rules.today) {
      const ok = await this.confirm.confirm({
        title: 'Start in the past?',
        message: `These rules start on ${effectiveFrom}, before today. Bonuses for the days since then will be recalculated with the new slabs. The change is recorded in the history.`,
        confirmLabel: 'Save anyway',
        danger: true,
        icon: 'ti-history',
      });
      if (!ok) return;
    }
    this.saving.set(true);
    this.service
      .saveRules({ effectiveFrom, tiers, note: this.form.controls.note.value.trim() || null })
      .subscribe({
        next: (saved) => {
          this.saving.set(false);
          this.rules.set(saved);
          this.historyPage.set(0);
          this.resetEditor(saved);
          this.toasts.success('Incentive rules saved. The change is in the history below.');
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.toasts.error((err.error as { message?: string } | undefined)?.message ?? 'Could not save the rules.');
        },
      });
  }

  protected goToHistoryPage(page: number): void {
    this.historyPage.set(page);
  }

  protected setHistorySize(size: number): void {
    this.historySize.set(size);
    writePageSize('incentiveRuleHistory', size);
    this.historyPage.set(0);
  }

  protected statusLabel(version: IncentiveRuleVersion): string {
    switch (version.status) {
      case 'ACTIVE':
        return 'In force';
      case 'SCHEDULED':
        return 'Starts later';
      case 'REPLACED':
        return 'Replaced the same day';
      default:
        return 'Earlier version';
    }
  }

  protected statusTone(version: IncentiveRuleVersion): string {
    switch (version.status) {
      case 'ACTIVE':
        return 'tone-green';
      case 'SCHEDULED':
        return 'tone-blue';
      default:
        return 'tone-grey';
    }
  }
}
