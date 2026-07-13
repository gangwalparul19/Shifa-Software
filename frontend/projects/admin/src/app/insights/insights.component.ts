import { DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ApiError } from 'core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { RowActionsMenuComponent, RowAction } from '../shared/row-actions-menu.component';
import { ToastService } from '../shared/toast.service';
import { InsightsService } from './insights.service';
import {
  Insight,
  SEVERITY_LABELS,
  SEVERITY_ORDER,
  Severity,
  insightMetric,
  insightTypeLabel,
  severityGroupClass,
  severityPillClass,
} from './insights.model';

/** The severity-filter lens options for the insights list. */
type SeverityLens = Severity | 'ALL';

/** A severity-grouped bundle of insights for the card list (DANGER → WARNING → INFO). */
interface SeverityGroup {
  severity: Severity;
  label: string;
  insights: Insight[];
}

/**
 * Statistical Insights screen (design §Frontend, Req 13.1, 13.2). Mobile-first,
 * mirroring the Leads pipeline:
 *
 * <ul>
 *   <li>a scrollable severity filter-tab lens (All / Danger / Warning / Info)
 *       carrying per-severity counts;</li>
 *   <li>insight cards grouped and colour-accented by severity (DANGER first,
 *       then WARNING, then INFO), each showing a severity pill + type label +
 *       title + detail + metric + computed date, with a "Dismiss" action;</li>
 *   <li>a prominent "Recompute" button that reruns the engine and reloads,
 *       disabled with a spinner while running;</li>
 *   <li>a friendly empty state prompting a Recompute when the list is empty.</li>
 * </ul>
 *
 * Role scoping and the ADMIN-only recompute/dismiss guards are enforced
 * server-side; the client surfaces errors as toasts. Reached from the ADMIN
 * hamburger nav ({@code /insights}, guarded ADMIN-only in the routes).
 */
@Component({
  selector: 'admin-insights',
  imports: [
    DatePipe,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    RowActionsMenuComponent,
  ],
  templateUrl: './insights.component.html',
  styleUrl: './insights.component.css',
})
export class InsightsComponent implements OnInit {
  private readonly service = inject(InsightsService);
  private readonly toasts = inject(ToastService);

  // --- Constants exposed to the template ---------------------------------
  protected readonly severityLabels = SEVERITY_LABELS;
  protected readonly pillClass = severityPillClass;
  protected readonly groupClass = severityGroupClass;
  protected readonly typeLabel = insightTypeLabel;
  protected readonly metricOf = insightMetric;

  /** The severity filter tabs shown above the list, in display order. */
  protected readonly severityTabs: { key: SeverityLens; label: string }[] = [
    { key: 'ALL', label: 'All' },
    ...SEVERITY_ORDER.map((s) => ({ key: s as SeverityLens, label: SEVERITY_LABELS[s] })),
  ];

  // --- List state ---------------------------------------------------------
  protected readonly insights = signal<Insight[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly severityLens = signal<SeverityLens>('ALL');

  /** Whether a recompute run is in flight (disables the button + shows a spinner). */
  protected readonly recomputing = signal(false);

  /** The loaded insights filtered by the active severity lens. */
  protected readonly visibleInsights = computed<Insight[]>(() => {
    const lens = this.severityLens();
    const rows = this.insights();
    if (lens === 'ALL') {
      return rows;
    }
    return rows.filter((i) => i.severity === lens);
  });

  // --- Client-side paging (over the post-filter flat list) ----------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('insights', 10));
  protected readonly totalElements = computed(() => this.visibleInsights().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  /** The current page slice of the flat filtered list (before grouping). */
  protected readonly pageItems = computed<Insight[]>(() => {
    const s = this.page() * this.size();
    return this.visibleInsights().slice(s, s + this.size());
  });

  /**
   * The current page's insights bundled into severity groups in DANGER →
   * WARNING → INFO order, dropping empty groups. Drives the grouped,
   * colour-accented card list.
   */
  protected readonly groups = computed<SeverityGroup[]>(() => {
    const rows = this.pageItems();
    return SEVERITY_ORDER.map((severity) => ({
      severity,
      label: SEVERITY_LABELS[severity],
      insights: rows.filter((i) => i.severity === severity),
    })).filter((g) => g.insights.length > 0);
  });

  /** Per-severity counts derived from the loaded insights (drives the tab badges). */
  protected readonly severityCounts = computed<Record<SeverityLens, number>>(() => {
    const counts: Record<SeverityLens, number> = { ALL: 0, DANGER: 0, WARNING: 0, INFO: 0 };
    for (const insight of this.insights()) {
      counts.ALL += 1;
      counts[insight.severity] += 1;
    }
    return counts;
  });

  ngOnInit(): void {
    this.load();
  }

  // --- Loading ------------------------------------------------------------

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (rows) => {
        this.insights.set(rows);
        this.page.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load insights. Please try again.');
        this.loading.set(false);
      },
    });
  }

  setLens(lens: SeverityLens): void {
    this.severityLens.set(lens);
    this.page.set(0);
  }

  // --- Paging handlers ----------------------------------------------------
  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('insights', s);
    this.page.set(0);
  }

  // --- Recompute ----------------------------------------------------------

  /** Reruns the insight engine now (ADMIN), then reloads the list (Req 2.2, 13.2). */
  recompute(): void {
    if (this.recomputing()) {
      return;
    }
    this.recomputing.set(true);
    this.service.recompute().subscribe({
      next: (res) => {
        this.recomputing.set(false);
        this.toasts.success(
          res.computed === 0
            ? 'Recompute finished — no insights for today.'
            : `Recompute finished — ${res.computed} insight${res.computed === 1 ? '' : 's'}.`,
        );
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.recomputing.set(false);
        this.toasts.error(this.messageOf(err) ?? 'Could not recompute insights. Please try again.');
      },
    });
  }

  // --- Dismiss ------------------------------------------------------------

  /** Per-row kebab action mirroring the original Dismiss button. */
  rowActions(insight: Insight): RowAction[] {
    return [{ key: 'dismiss', label: 'Dismiss', icon: 'ti-check' }];
  }

  /** Dispatches a kebab action for the given insight card. */
  onRowAction(key: string, insight: Insight): void {
    if (key === 'dismiss') {
      this.dismiss(insight);
    }
  }

  /** Dismisses one insight (ADMIN, idempotent) and removes it from the list (Req 9.3). */
  dismiss(insight: Insight): void {
    this.service.dismiss(insight.id).subscribe({
      next: () => {
        this.insights.update((rows) => rows.filter((i) => i.id !== insight.id));
        // Avoid being stranded on a now-empty trailing page.
        const maxPage = Math.max(0, this.totalPages() - 1);
        if (this.page() > maxPage) {
          this.page.set(maxPage);
        }
        this.toasts.info('Insight dismissed.');
      },
      error: (err: HttpErrorResponse) => {
        this.toasts.error(this.messageOf(err) ?? 'Could not dismiss the insight.');
      },
    });
  }

  // --- Helpers ------------------------------------------------------------

  private messageOf(err: HttpErrorResponse): string | null {
    const body = err.error as ApiError | undefined;
    return body?.message ?? null;
  }
}
