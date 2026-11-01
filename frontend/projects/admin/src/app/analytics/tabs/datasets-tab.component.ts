import { Component, OnDestroy, OnInit, computed, inject, input, signal } from '@angular/core';
import { formatDateLabel } from '../../shared/date-label.util';
import { StatePanelComponent } from '../../shared/state-panel.component';
import { ToastService } from '../../shared/toast.service';
import { AnalyticsService } from '../analytics.service';
import { DatasetInfo } from '../analytics.model';

const DOWNLOAD_ERROR = 'Could not download. Choose a shorter period or try again.';

/** The one dataset that carries names and mobile numbers. */
const PERSONAL_DATASET_KEY = 'customers';

/** Datasets that can run past the row limit on a long period. */
const LARGE_DATASET_KEYS: readonly string[] = ['daily_sales', 'orders', 'order_items'];

/**
 * The Datasets tab of the Analytics page: documented, flat CSV extracts that
 * analysts and data engineers can load into a spreadsheet, Power BI, Metabase or
 * a notebook. The catalog (and every column) comes from the server, so the page
 * never goes out of date with the export.
 */
@Component({
  selector: 'admin-analytics-datasets',
  standalone: true,
  imports: [StatePanelComponent],
  templateUrl: './datasets-tab.component.html',
  styleUrl: './datasets-tab.component.css',
})
export class DatasetsTabComponent implements OnInit, OnDestroy {
  private readonly service = inject(AnalyticsService);
  private readonly toasts = inject(ToastService);

  /** The period chosen on the Analytics page (ISO dates); both null means the server default. */
  readonly from = input<string | null>(null);
  readonly to = input<string | null>(null);

  protected readonly datasets = signal<DatasetInfo[]>([]);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);

  /** Datasets whose column table is open, keyed by dataset key. */
  private readonly expanded = signal<Record<string, boolean>>({});
  /** Datasets being downloaded right now, keyed by dataset key. */
  private readonly downloading = signal<Record<string, boolean>>({});
  /** The dataset whose column list was just copied (shows "Copied" for two seconds). */
  protected readonly copiedKey = signal<string | null>(null);

  private copyTimer: ReturnType<typeof setTimeout> | undefined;

  protected readonly rangeLabel = computed(() => {
    const from = this.from();
    const to = this.to();
    if (!from && !to) {
      return 'Last 90 days';
    }
    if (from && to) {
      return `${formatDateLabel(from)} to ${formatDateLabel(to)}`;
    }
    if (from) {
      return `From ${formatDateLabel(from)} to today`;
    }
    return `The 90 days up to ${formatDateLabel(to)}`;
  });

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    clearTimeout(this.copyTimer);
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.service.datasets().subscribe({
      next: (list) => {
        this.datasets.set(list);
        this.loading.set(false);
      },
      error: () => {
        this.error.set('Could not load the data downloads. Please try again.');
        this.loading.set(false);
      },
    });
  }

  // --- Per-dataset state --------------------------------------------------

  protected isPersonal(dataset: DatasetInfo): boolean {
    return dataset.key === PERSONAL_DATASET_KEY;
  }

  protected isLarge(dataset: DatasetInfo): boolean {
    return LARGE_DATASET_KEYS.includes(dataset.key);
  }

  protected isExpanded(key: string): boolean {
    return this.expanded()[key] === true;
  }

  protected toggleColumns(key: string): void {
    this.expanded.update((state) => ({ ...state, [key]: !state[key] }));
  }

  protected isDownloading(key: string): boolean {
    return this.downloading()[key] === true;
  }

  // --- Download -----------------------------------------------------------

  protected download(dataset: DatasetInfo): void {
    if (this.isDownloading(dataset.key)) {
      return;
    }
    this.setDownloading(dataset.key, true);
    this.service.downloadDataset(dataset.key, this.from(), this.to()).subscribe({
      next: (file) => {
        try {
          this.service.saveDataset(file, dataset.key);
          this.toasts.success(`${dataset.label} downloaded.`);
        } catch {
          this.toasts.error(DOWNLOAD_ERROR);
        }
        this.setDownloading(dataset.key, false);
      },
      error: () => {
        this.toasts.error(DOWNLOAD_ERROR);
        this.setDownloading(dataset.key, false);
      },
    });
  }

  private setDownloading(key: string, on: boolean): void {
    this.downloading.update((state) => ({ ...state, [key]: on }));
  }

  // --- Copy the column list -----------------------------------------------

  protected async copyColumns(dataset: DatasetInfo): Promise<void> {
    const text = dataset.columns.map((c) => `${c.name}  ${c.type}  ${c.description}`).join('\n');
    if (await this.writeToClipboard(text)) {
      this.copiedKey.set(dataset.key);
      clearTimeout(this.copyTimer);
      this.copyTimer = setTimeout(() => this.copiedKey.set(null), 2000);
    } else {
      this.toasts.error('Could not copy. Open the Columns table and copy it by hand.');
    }
  }

  private async writeToClipboard(text: string): Promise<boolean> {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(text);
        return true;
      }
    } catch {
      // Blocked or unavailable (for example a plain-HTTP page): use the fallback below.
    }
    return this.copyWithTextarea(text);
  }

  /** Older-browser fallback: select the text in a hidden field and copy it. */
  private copyWithTextarea(text: string): boolean {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.top = '0';
    area.style.opacity = '0';
    document.body.appendChild(area);
    try {
      area.select();
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      area.remove();
    }
  }
}
