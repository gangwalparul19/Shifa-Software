import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DialogDirective } from '../shared/dialog.directive';
import { CompactInrPipe, InrPipe, formatInr } from '../shared/inr.pipe';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { istToday, periodProblem } from '../analytics/analytics-period.util';
import {
  IncentiveReportFormat,
  IncentiveReportKind,
  IncentiveTracker,
  ProductReport,
  TrackerDayCell,
  TrackerDayInfo,
  TrackerDayRef,
  TrackerPerson,
} from './incentives.model';
import { IncentiveReportQuery, IncentivesService } from './incentives.service';
import {
  addMonths,
  calendarWeeks,
  monthLabel,
  monthOf,
  monthRange,
  shortDate,
  weekdayShort,
} from './incentives.util';

type IncentiveTab = 'tracker' | 'calendar' | 'products';

/** The day whose products the side panel is showing. */
interface OpenDay {
  person: TrackerPerson;
  cell: TrackerDayCell;
}

/**
 * Daily Sales &amp; Incentives (ADMIN). One page, three views of the same month:
 * <ul>
 *   <li><b>Tracker</b>: every salesperson by day, with the daily bonus they earned;</li>
 *   <li><b>Calendar</b>: one person's month on a calendar, with the highest and lowest sales days
 *       (Sundays are never used for those);</li>
 *   <li><b>Products</b>: what each person sold each day, product by product.</li>
 * </ul>
 * Each view downloads as Excel or PDF. The rules that decide the bonus are edited in Settings.
 */
@Component({
  selector: 'admin-incentives',
  standalone: true,
  imports: [
    RouterLink,
    DialogDirective,
    InrPipe,
    CompactInrPipe,
    IstDatePipe,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
  ],
  templateUrl: './incentives.component.html',
  styleUrl: './incentives.component.css',
})
export class IncentivesComponent implements OnInit {
  private readonly service = inject(IncentivesService);
  private readonly toasts = inject(ToastService);

  protected readonly tabs: { key: IncentiveTab; label: string; icon: string }[] = [
    { key: 'tracker', label: 'Daily tracker', icon: 'ti-table' },
    { key: 'calendar', label: 'Month calendar', icon: 'ti-calendar-stats' },
    { key: 'products', label: 'Products by day', icon: 'ti-packages' },
  ];
  protected readonly weekdayHeads = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

  protected readonly today = istToday();
  protected readonly thisMonth = monthOf(this.today);

  protected readonly tab = signal<IncentiveTab>('tracker');
  protected readonly month = signal(this.thisMonth);
  protected readonly data = signal<IncentiveTracker | null>(null);
  protected readonly loading = signal(true);
  protected readonly failed = signal(false);
  private trackerVersion = 0;

  /** The person shown on the calendar (null until the month has loaded). */
  protected readonly selectedId = signal<number | null>(null);

  // --- Day side panel ------------------------------------------------------
  protected readonly openDayState = signal<OpenDay | null>(null);
  protected readonly dayReport = signal<ProductReport | null>(null);
  protected readonly dayLoading = signal(false);
  protected readonly dayFailed = signal(false);
  private dayVersion = 0;

  // --- Products view -------------------------------------------------------
  protected readonly pFrom = signal('');
  protected readonly pTo = signal('');
  protected readonly pPerson = signal<number | null>(null);
  protected readonly report = signal<ProductReport | null>(null);
  protected readonly reportLoading = signal(false);
  protected readonly reportFailed = signal(false);
  protected readonly reportProblem = signal<string | null>(null);
  private reportVersion = 0;
  protected readonly rowPage = signal(0);
  protected readonly rowSize = signal(readPageSize('incentiveProducts', 20));

  /** Which download (xlsx / pdf) is being prepared. */
  protected readonly exporting = signal<IncentiveReportFormat | null>(null);

  protected readonly formatInr = formatInr;
  protected readonly shortDate = shortDate;

  // --- Derived -------------------------------------------------------------
  protected readonly canGoNext = computed(() => this.month() < this.thisMonth);
  protected readonly monthTitle = computed(() => monthLabel(this.month()));

  protected readonly selectedPerson = computed<TrackerPerson | null>(() => {
    const people = this.data()?.people ?? [];
    return people.find((p) => p.personId === this.selectedId()) ?? people[0] ?? null;
  });

  protected readonly weeks = computed(() => {
    const d = this.data();
    const person = this.selectedPerson();
    return d && person ? calendarWeeks(d.days, person.days) : [];
  });

  /** Salespeople who sold something this month. */
  protected readonly sellingCount = computed(() => (this.data()?.people ?? []).filter((p) => p.orders > 0).length);

  /** The selected person's days up to today, for the day-by-day list under the calendar. */
  protected readonly personDays = computed(() => {
    const d = this.data();
    const person = this.selectedPerson();
    if (!d || !person) {
      return [];
    }
    return d.days
      .map((info, i) => ({ info, cell: person.days[i] }))
      .filter((x) => !x.info.future);
  });

  protected readonly rowTotal = computed(() => this.report()?.rows.length ?? 0);
  protected readonly rowPages = computed(() => Math.max(1, Math.ceil(this.rowTotal() / this.rowSize())));
  protected readonly rowItems = computed(() => {
    const start = this.rowPage() * this.rowSize();
    return (this.report()?.rows ?? []).slice(start, start + this.rowSize());
  });

  ngOnInit(): void {
    this.resetProductRange();
    this.loadTracker();
  }

  // --- Month ---------------------------------------------------------------

  protected shiftMonth(delta: number): void {
    this.setMonth(addMonths(this.month(), delta));
  }

  protected goThisMonth(): void {
    this.setMonth(this.thisMonth);
  }

  private setMonth(month: string): void {
    if (month > this.thisMonth || month === this.month()) {
      return;
    }
    this.month.set(month);
    this.resetProductRange();
    this.report.set(null);
    this.loadTracker();
    if (this.tab() === 'products') {
      this.loadReport();
    }
  }

  protected loadTracker(): void {
    const version = ++this.trackerVersion;
    this.loading.set(true);
    this.failed.set(false);
    this.service.tracker(this.month()).subscribe({
      next: (data) => {
        if (version !== this.trackerVersion) {
          return; // a newer month was asked for meanwhile
        }
        this.data.set(data);
        this.loading.set(false);
        if (!data.people.some((p) => p.personId === this.selectedId())) {
          this.selectedId.set(data.people[0]?.personId ?? null);
        }
      },
      error: () => {
        if (version !== this.trackerVersion) {
          return;
        }
        this.loading.set(false);
        this.failed.set(true);
      },
    });
  }

  // --- Tabs ----------------------------------------------------------------

  protected selectTab(tab: IncentiveTab): void {
    this.tab.set(tab);
    if (tab === 'products' && !this.report() && !this.reportLoading()) {
      this.loadReport();
    }
  }

  protected selectPerson(value: string): void {
    this.selectedId.set(value ? Number(value) : null);
  }

  /** From the tracker: jump to this person's calendar. */
  protected openCalendar(person: TrackerPerson): void {
    this.selectedId.set(person.personId);
    this.tab.set('calendar');
    if (typeof window !== 'undefined') {
      window.scrollTo({ top: 0, behavior: 'smooth' });
    }
  }

  // --- Day side panel ------------------------------------------------------

  protected openDay(person: TrackerPerson, cell: TrackerDayCell): void {
    if (cell.orders === 0) {
      return;
    }
    this.openDayState.set({ person, cell });
    this.dayReport.set(null);
    this.dayFailed.set(false);
    this.dayLoading.set(true);
    const version = ++this.dayVersion;
    this.service.products(cell.date, cell.date, person.personId).subscribe({
      next: (report) => {
        if (version !== this.dayVersion) return;
        this.dayReport.set(report);
        this.dayLoading.set(false);
      },
      error: () => {
        if (version !== this.dayVersion) return;
        this.dayLoading.set(false);
        this.dayFailed.set(true);
      },
    });
  }

  protected closeDay(): void {
    this.dayVersion++;
    this.openDayState.set(null);
  }

  // --- Products view -------------------------------------------------------

  private resetProductRange(): void {
    const range = monthRange(this.month());
    this.pFrom.set(range.from);
    this.pTo.set(this.month() === this.thisMonth ? this.today : range.to);
    this.pPerson.set(null);
  }

  protected setProductPerson(value: string): void {
    this.pPerson.set(value ? Number(value) : null);
  }

  protected loadReport(): void {
    const problem = periodProblem(this.pFrom(), this.pTo(), 366);
    this.reportProblem.set(problem);
    if (problem) {
      return;
    }
    const version = ++this.reportVersion;
    this.reportLoading.set(true);
    this.reportFailed.set(false);
    this.service.products(this.pFrom(), this.pTo(), this.pPerson()).subscribe({
      next: (report) => {
        if (version !== this.reportVersion) return;
        this.report.set(report);
        this.rowPage.set(0);
        this.reportLoading.set(false);
      },
      error: () => {
        if (version !== this.reportVersion) return;
        this.reportLoading.set(false);
        this.reportFailed.set(true);
      },
    });
  }

  protected goToRowPage(page: number): void {
    this.rowPage.set(page);
  }

  protected setRowSize(size: number): void {
    this.rowSize.set(size);
    writePageSize('incentiveProducts', size);
    this.rowPage.set(0);
  }

  // --- Downloads -----------------------------------------------------------

  /** Whether the current view has enough chosen to be downloaded. */
  protected readonly canExport = computed(() => {
    switch (this.tab()) {
      case 'calendar':
        return this.selectedPerson() !== null;
      case 'products':
        return periodProblem(this.pFrom(), this.pTo(), 366) === null;
      default:
        return true;
    }
  });

  protected exportCurrent(format: IncentiveReportFormat): void {
    if (this.exporting() || !this.canExport()) {
      return;
    }
    const kind: IncentiveReportKind = this.tab();
    const query: IncentiveReportQuery =
      kind === 'tracker'
        ? { month: this.month() }
        : kind === 'calendar'
          ? { month: this.month(), personId: this.selectedPerson()?.personId }
          : { from: this.pFrom(), to: this.pTo(), personId: this.pPerson() };
    this.exporting.set(format);
    this.service.download(kind, format, query).subscribe({
      next: (download) => {
        this.exporting.set(null);
        this.service.save(download, kind, format);
      },
      error: () => {
        this.exporting.set(null);
        this.toasts.error('Could not prepare the download. Please try again.');
      },
    });
  }

  // --- Template helpers ----------------------------------------------------

  protected dayNumber(info: TrackerDayInfo): number {
    return Number(info.date.slice(8));
  }

  protected initial(info: TrackerDayInfo): string {
    return info.weekday.charAt(0);
  }

  /** "5 Nov · ₹41,200" with "(+1 tied)" when more days share the figure; a dash when there is none. */
  protected refText(refs: TrackerDayRef[]): string {
    if (refs.length === 0) {
      return '—';
    }
    const first = refs[0];
    return `${shortDate(first.date)} · ${formatInr(first.sales, 0)}${refs.length > 1 ? ` (+${refs.length - 1} tied)` : ''}`;
  }

  /** Day total minus product lines, to the paisa, so float noise never shows as a difference. */
  protected gap(dayTotal: number, lineTotal: number): number {
    return Math.round((dayTotal - lineTotal) * 100) / 100;
  }

  protected fullDate(date: string): string {
    return `${weekdayShort(date)}, ${shortDate(date)} ${date.slice(0, 4)}`;
  }

  /** What the day earned, in words, for tooltips and screen readers. */
  protected cellTitle(info: TrackerDayInfo, cell: TrackerDayCell): string {
    const parts = [this.fullDate(info.date)];
    if (cell.orders === 0) {
      parts.push(info.future ? 'not reached yet' : info.sunday ? 'Sunday, no sales' : 'no sales');
      return parts.join(' - ');
    }
    parts.push(`${formatInr(cell.sales, 0)} from ${cell.orders} order${cell.orders === 1 ? '' : 's'}`);
    if (cell.bonus > 0) {
      parts.push(`bonus ${formatInr(cell.bonus, 0)}`);
    }
    if (cell.highest) parts.push('highest day');
    if (cell.lowest) parts.push('lowest day');
    if (info.sunday) parts.push('Sunday: not used for highest/lowest');
    return parts.join(' - ');
  }

  protected remarks(info: TrackerDayInfo, cell: TrackerDayCell): string[] {
    const out: string[] = [];
    if (cell.highest) out.push('Highest day');
    if (cell.lowest) out.push('Lowest day');
    if (info.sunday) out.push('Sunday');
    if (cell.orders === 0 && !info.sunday) out.push('No sales');
    return out;
  }
}
