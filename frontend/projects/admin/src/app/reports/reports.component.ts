import { Component, OnInit, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { DownloadResult, ReportsService } from './reports.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import {
  DatePreset,
  ExportFormat,
  ReportResponse,
  ReportType,
  ReportTypeOption,
  VyaparFormat,
} from './reports.model';

interface Toast {
  kind: 'ok' | 'error';
  text: string;
}

/**
 * Reporting and export view (Req 20.1, 20.2, 20.4, 23.1).
 *
 * <p>Pick a report type (daily/monthly/product/state/salesperson) and a date
 * range — via quick presets (Today, Last 7, Last 30, This Month) or a custom
 * from/to — then generate a results table with headline metrics. Export buttons
 * download the current report as Excel or PDF, or the Vyapar billing file
 * (CSV/Excel); the Vyapar empty-range "no orders" notice is surfaced as a toast
 * (Req 23.2). The route is guarded for ADMIN + ACCOUNTANT; the backend also
 * scopes a salesperson to their own orders if reached directly.
 */
@Component({
  selector: 'admin-reports',
  imports: [ReactiveFormsModule, PageHeaderComponent],
  templateUrl: './reports.component.html',
  styleUrl: './reports.component.css',
})
export class ReportsComponent implements OnInit {
  private readonly service = inject(ReportsService);
  private readonly fb = inject(FormBuilder);

  protected readonly reportTypes: ReportTypeOption[] = [
    { value: 'daily', label: 'Daily' },
    { value: 'monthly', label: 'Monthly' },
    { value: 'product', label: 'Product-wise' },
    { value: 'state', label: 'State-wise' },
    { value: 'salesperson', label: 'Salesperson-wise' },
  ];

  protected readonly presets: DatePreset[] = [
    { key: 'today', label: 'Today' },
    { key: 'last7', label: 'Last 7 Days' },
    { key: 'last30', label: 'Last 30 Days' },
    { key: 'month', label: 'This Month' },
    { key: 'custom', label: 'Custom' },
  ];

  protected readonly form = this.fb.nonNullable.group({
    type: 'daily' as ReportType,
    preset: 'last30',
    from: '',
    to: '',
  });

  protected readonly report = signal<ReportResponse | null>(null);
  protected readonly loading = signal(false);
  protected readonly exporting = signal(false);
  protected readonly loadError = signal<string | null>(null);
  protected readonly toast = signal<Toast | null>(null);

  private toastTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    this.applyPreset('last30');
    this.generate();
  }

  /** Whether the custom from/to inputs are active. */
  protected isCustom(): boolean {
    return this.form.controls.preset.value === 'custom';
  }

  onPresetChange(key: string): void {
    this.form.controls.preset.setValue(key);
    if (key !== 'custom') {
      this.applyPreset(key);
    }
  }

  /** Resolves a preset key into concrete from/to ISO dates in the form. */
  private applyPreset(key: string): void {
    const today = new Date();
    const iso = (d: Date) => d.toISOString().slice(0, 10);
    let from = '';
    let to = iso(today);
    switch (key) {
      case 'today':
        from = iso(today);
        break;
      case 'last7': {
        const d = new Date(today);
        d.setDate(d.getDate() - 6);
        from = iso(d);
        break;
      }
      case 'last30': {
        const d = new Date(today);
        d.setDate(d.getDate() - 29);
        from = iso(d);
        break;
      }
      case 'month': {
        from = iso(new Date(today.getFullYear(), today.getMonth(), 1));
        break;
      }
      default:
        return;
    }
    this.form.controls.from.setValue(from);
    this.form.controls.to.setValue(to);
  }

  private currentRange(): { from: string | null; to: string | null } {
    const from = this.form.controls.from.value || null;
    const to = this.form.controls.to.value || null;
    return { from, to };
  }

  generate(): void {
    const type = this.form.controls.type.value;
    const { from, to } = this.currentRange();
    this.loading.set(true);
    this.loadError.set(null);
    this.service.report(type, from, to).subscribe({
      next: (r) => {
        this.report.set(r);
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.loadError.set('Could not generate the report. Please try again.');
      },
    });
  }

  exportFile(format: ExportFormat): void {
    const type = this.form.controls.type.value;
    const { from, to } = this.currentRange();
    this.exporting.set(true);
    this.service.export(type, format, from, to).subscribe({
      next: (result) => {
        this.exporting.set(false);
        this.saveFile(result);
        this.showToast('ok', `Exported ${format.toUpperCase()} file.`);
      },
      error: () => {
        this.exporting.set(false);
        this.showToast('error', 'Export failed. Please try again.');
      },
    });
  }

  exportVyapar(format: VyaparFormat): void {
    const { from, to } = this.currentRange();
    this.exporting.set(true);
    this.service.vyapar(format, from, to).subscribe({
      next: (result) => {
        this.exporting.set(false);
        this.saveFile(result);
        if (result.message) {
          this.showToast('error', result.message);
        } else {
          this.showToast('ok', `Exported Vyapar ${format.toUpperCase()} file.`);
        }
      },
      error: () => {
        this.exporting.set(false);
        this.showToast('error', 'Vyapar export failed. Please try again.');
      },
    });
  }

  /** Triggers a browser download of the exported file blob. */
  private saveFile(result: DownloadResult): void {
    const url = URL.createObjectURL(result.blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = result.filename;
    document.body.appendChild(anchor);
    anchor.click();
    document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  }

  private showToast(kind: Toast['kind'], text: string): void {
    this.toast.set({ kind, text });
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), 5000);
  }
}
