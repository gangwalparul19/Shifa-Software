import { CommonModule } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { ToastService } from '../shared/toast.service';
import { FilingService } from './filing.service';
import {
  CalendarEntry,
  CalendarResponse,
  ReturnType,
  Snapshot,
  filingStatusLabel,
  filingStatusPillClass,
  overdueBadgeClass,
  reminderBadgeClass,
  returnTypeLabel,
} from './filing.model';

/** One month of the filing calendar, pairing its GSTR-1 and GSTR-3B entries. */
interface MonthRow {
  month: number;
  year: number;
  /** Human label, e.g. "April 2025". */
  label: string;
  gstr1: CalendarEntry | null;
  gstr3b: CalendarEntry | null;
}

/**
 * GST returns filing workspace (Phase 3 — GST returns & filing, Reqs 1.6, 2.1, 4.1–4.4, 5.4, 5.7,
 * 6.1, 6.2). Presents the per-month filing calendar for a chosen Indian financial year with GSTR-1
 * and GSTR-3B due dates, status pills and reminder/overdue/due-today badges; offers the single valid
 * lifecycle action per period (Prepare / File with an acknowledgement reference / Reopen); shows an
 * explicit read-only lock indicator on FILED periods (Req 2.1); a per-return snapshot-history viewer;
 * and filing-aware GSTR-1 export (CSV ZIP / portal JSON) with a browser download.
 */
@Component({
  selector: 'admin-gst-filing',
  standalone: true,
  imports: [CommonModule, FormsModule, PageHeaderComponent],
  templateUrl: './gst-filing.component.html',
  styleUrl: './gst-filing.component.css',
})
export class GstFilingComponent implements OnInit {
  private readonly filing = inject(FilingService);
  private readonly toasts = inject(ToastService);

  // --- Calendar state ------------------------------------------------------
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly calendar = signal<CalendarResponse | null>(null);

  /** The selected Indian financial-year start year (April of this year → March next). */
  protected readonly fyStartYear = signal<number>(this.currentFyStartYear());

  /** The FY start years offered by the picker (most recent first). */
  protected readonly fyOptions = computed<number[]>(() => {
    const cur = this.currentFyStartYear();
    const years: number[] = [];
    for (let y = cur + 1; y >= cur - 5; y--) {
      years.push(y);
    }
    return years;
  });

  /** The calendar entries grouped into one row per month (Apr → Mar), each holding both returns. */
  protected readonly months = computed<MonthRow[]>(() => {
    const cal = this.calendar();
    if (!cal) {
      return [];
    }
    const start = cal.financialYearStartYear;
    // FY month sequence: Apr..Dec of the start year, then Jan..Mar of the next year.
    const sequence: Array<{ month: number; year: number }> = [];
    for (let m = 4; m <= 12; m++) {
      sequence.push({ month: m, year: start });
    }
    for (let m = 1; m <= 3; m++) {
      sequence.push({ month: m, year: start + 1 });
    }
    return sequence.map(({ month, year }) => ({
      month,
      year,
      label: this.monthLabel(month, year),
      gstr1: this.findEntry(cal.entries, 'GSTR1', month, year),
      gstr3b: this.findEntry(cal.entries, 'GSTR3B', month, year),
    }));
  });

  // --- Per-period action state --------------------------------------------
  /** The row key currently mid-action (disables its buttons); `null` when idle. */
  protected readonly busyKey = signal<string | null>(null);

  /** The row key whose "File" acknowledgement-reference input is open; `null` when none. */
  protected readonly fileTargetKey = signal<string | null>(null);

  /** The acknowledgement reference typed for the currently-open File input. */
  protected readonly ackReference = signal('');

  // --- Snapshot history drawer --------------------------------------------
  protected readonly historyOpen = signal(false);
  protected readonly historyTitle = signal('');
  protected readonly historyLoading = signal(false);
  protected readonly historySnapshots = signal<Snapshot[]>([]);

  // --- Export state --------------------------------------------------------
  /** The row key whose GSTR-1 export is in flight; `null` when none. */
  protected readonly exportingKey = signal<string | null>(null);

  // Re-export the shared pill/label helpers for the template.
  protected readonly filingStatusPillClass = filingStatusPillClass;
  protected readonly filingStatusLabel = filingStatusLabel;
  protected readonly returnTypeLabel = returnTypeLabel;
  protected readonly reminderBadgeClass = reminderBadgeClass;
  protected readonly overdueBadgeClass = overdueBadgeClass;

  ngOnInit(): void {
    this.load();
  }

  /** Load the filing calendar for the selected financial year. */
  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.filing.calendar(this.fyStartYear()).subscribe({
      next: (cal) => {
        this.calendar.set(cal);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the filing calendar. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Switch the selected financial year and reload. */
  onFyChange(value: string): void {
    const year = Number(value);
    if (Number.isFinite(year)) {
      this.fyStartYear.set(year);
      this.closeFileInput();
      this.load();
    }
  }

  /** A stable key identifying a period + return type row. */
  key(returnType: ReturnType, month: number, year: number): string {
    return `${returnType}-${year}-${String(month).padStart(2, '0')}`;
  }

  /* ── Lifecycle actions (only the one valid for the current status is offered) ── */

  /** Mark a NOT_STARTED return prepared (Reqs 1.3, 1.7). */
  prepare(entry: CalendarEntry): void {
    const k = this.key(entry.returnType, entry.month, entry.year);
    this.busyKey.set(k);
    this.filing.prepare({ month: entry.month, year: entry.year, returnType: entry.returnType }).subscribe({
      next: () => {
        this.busyKey.set(null);
        this.toasts.success(`${returnTypeLabel(entry.returnType)} ${entry.month}/${entry.year} marked prepared.`);
        this.load();
      },
      error: (e) => this.fail(e, 'Could not mark the return prepared.'),
    });
  }

  /** Open the acknowledgement-reference input for a PREPARED return before filing. */
  startFile(entry: CalendarEntry): void {
    this.fileTargetKey.set(this.key(entry.returnType, entry.month, entry.year));
    this.ackReference.set('');
  }

  /** Cancel the open File acknowledgement-reference input. */
  closeFileInput(): void {
    this.fileTargetKey.set(null);
    this.ackReference.set('');
  }

  /** File a PREPARED return with the (optional) acknowledgement reference (Reqs 1.4, 1.5). */
  confirmFile(entry: CalendarEntry): void {
    const k = this.key(entry.returnType, entry.month, entry.year);
    const ack = this.ackReference().trim();
    this.busyKey.set(k);
    this.filing
      .file({
        month: entry.month,
        year: entry.year,
        returnType: entry.returnType,
        ackReference: ack.length ? ack : null,
      })
      .subscribe({
        next: () => {
          this.busyKey.set(null);
          this.closeFileInput();
          this.toasts.success(`${returnTypeLabel(entry.returnType)} ${entry.month}/${entry.year} filed and locked.`);
          this.load();
        },
        error: (e) => this.fail(e, 'Could not file the return.'),
      });
  }

  /** Reopen a FILED return back to PREPARED (ADMIN/CA only, Reqs 2.4, 2.7). */
  reopen(entry: CalendarEntry): void {
    const k = this.key(entry.returnType, entry.month, entry.year);
    this.busyKey.set(k);
    this.filing.reopen({ month: entry.month, year: entry.year, returnType: entry.returnType }).subscribe({
      next: () => {
        this.busyKey.set(null);
        this.toasts.success(`${returnTypeLabel(entry.returnType)} ${entry.month}/${entry.year} reopened.`);
        this.load();
      },
      error: (e) => this.fail(e, 'Could not reopen the return.'),
    });
  }

  /* ── Snapshot history (Req 5.7) ─────────────────────────────────────────── */

  /** Open the snapshot-history drawer for a period + return type. */
  openHistory(entry: CalendarEntry): void {
    this.historyTitle.set(`${returnTypeLabel(entry.returnType)} · ${this.monthLabel(entry.month, entry.year)}`);
    this.historyOpen.set(true);
    this.historyLoading.set(true);
    this.historySnapshots.set([]);
    this.filing.snapshots(entry.month, entry.year, entry.returnType).subscribe({
      next: (rows) => {
        this.historySnapshots.set(rows);
        this.historyLoading.set(false);
      },
      error: () => {
        this.historyLoading.set(false);
        this.toasts.error('Could not load the filing history.');
      },
    });
  }

  closeHistory(): void {
    this.historyOpen.set(false);
  }

  /* ── Filing-aware GSTR-1 export (Reqs 6.1, 6.2) ─────────────────────────── */

  /**
   * Download the filing-aware GSTR-1 export for a month: {@code csv} = ZIP of section CSVs,
   * {@code json} = portal JSON (served from the snapshot when FILED). Mirrors the dashboard's
   * blob-download pattern.
   */
  exportGstr1(month: number, year: number, format: 'csv' | 'json'): void {
    const k = `EXP-${year}-${month}-${format}`;
    this.exportingKey.set(k);
    this.filing.exportGstr1(month, year, format).subscribe({
      next: (blob) => {
        this.exportingKey.set(null);
        const ext = format === 'csv' ? 'zip' : 'json';
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `gstr1-${year}-${String(month).padStart(2, '0')}.${ext}`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => {
        this.exportingKey.set(null);
        this.toasts.error('Could not export GSTR-1. The seller GSTIN may be missing in Settings.');
      },
    });
  }

  /** A friendly financial-year label, e.g. "FY 2025-26". */
  fyLabel(startYear: number): string {
    const end = (startYear + 1) % 100;
    return `FY ${startYear}-${String(end).padStart(2, '0')}`;
  }

  /** Format an ISO `yyyy-MM-dd` due date as a readable date. */
  fmtDate(iso: string): string {
    if (!iso) {
      return '—';
    }
    const d = new Date(iso + 'T00:00:00');
    return Number.isNaN(d.getTime())
      ? iso
      : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
  }

  /** Format an ISO date-time (snapshot timestamp) as a readable date-time. */
  fmtDateTime(iso: string): string {
    if (!iso) {
      return '—';
    }
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString('en-IN');
  }

  // --- internal helpers ----------------------------------------------------

  /** The Indian FY start year (April onwards → this year, else the previous year) for today. */
  private currentFyStartYear(): number {
    const t = new Date();
    return t.getMonth() >= 3 ? t.getFullYear() : t.getFullYear() - 1;
  }

  private monthLabel(month: number, year: number): string {
    const name = new Date(year, month - 1, 1).toLocaleString('en-IN', { month: 'long' });
    return `${name} ${year}`;
  }

  private findEntry(
    entries: CalendarEntry[],
    returnType: ReturnType,
    month: number,
    year: number,
  ): CalendarEntry | null {
    return entries.find((e) => e.returnType === returnType && e.month === month && e.year === year) ?? null;
  }

  private fail(err: unknown, fallback: string): void {
    this.busyKey.set(null);
    const status = (err as { status?: number })?.status;
    if (status === 409) {
      this.toasts.error('That action is not allowed for the current status (the period may be locked).');
    } else if (status === 403) {
      this.toasts.error('You are not authorized to perform this action.');
    } else {
      this.toasts.error(fallback);
    }
    this.load();
  }
}
