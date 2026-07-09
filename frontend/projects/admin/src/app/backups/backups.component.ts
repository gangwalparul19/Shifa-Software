import { DatePipe } from '@angular/common';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';
import { BackupsService } from './backups.service';
import { BackupRun } from './backups.model';

/**
 * Admin database Backups (ADMIN only, Req 24.1, 24.2).
 *
 * <p>Surfaces the backend backup capability: a "Run backup now" action that
 * triggers {@code POST /api/admin/backups/run} (the same routine the nightly
 * scheduler uses) and a history list of recent runs from
 * {@code GET /api/admin/backups} with their status, timing and any error.
 */
@Component({
  selector: 'admin-backups',
  imports: [DatePipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './backups.component.html',
  styleUrl: './backups.component.css',
})
export class BackupsComponent implements OnInit {
  private readonly service = inject(BackupsService);
  private readonly confirm = inject(ConfirmService);
  private readonly toasts = inject(ToastService);

  protected readonly runs = signal<BackupRun[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly running = signal(false);

  /** The most recent successful run, for the summary tile. */
  protected readonly lastSuccess = computed<BackupRun | null>(
    () => this.runs().find((r) => this.isSuccess(r.status)) ?? null,
  );

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.history().subscribe({
      next: (rows) => {
        this.runs.set(rows);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load backup history. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Triggers a backup now (ADMIN), then reloads the history. */
  async runNow(): Promise<void> {
    if (this.running()) {
      return;
    }
    const confirmed = await this.confirm.confirm({
      title: 'Run backup now',
      message: 'Start a database backup immediately? This runs the same routine as the nightly job.',
      confirmLabel: 'Run backup',
      icon: 'ti-database-export',
    });
    if (!confirmed) {
      return;
    }
    this.running.set(true);
    this.service.run().subscribe({
      next: (result) => {
        this.running.set(false);
        if (result.success) {
          this.toasts.success('Backup completed.');
        } else {
          this.toasts.error(result.error ? `Backup failed: ${result.error}` : 'Backup failed.');
        }
        this.load();
      },
      error: () => {
        this.running.set(false);
        this.toasts.error('Could not start the backup. Please try again.');
      },
    });
  }

  // --- Presentation helpers ----------------------------------------------

  isSuccess(status: string | null | undefined): boolean {
    return (status ?? '').toUpperCase() === 'SUCCESS';
  }

  isFailed(status: string | null | undefined): boolean {
    return (status ?? '').toUpperCase() === 'FAILED';
  }

  /** Tabler badge classes for a run status: green success, red failure, amber otherwise. */
  statusBadgeClass(status: string | null | undefined): string {
    if (this.isSuccess(status)) {
      return 'bg-green-lt';
    }
    if (this.isFailed(status)) {
      return 'bg-red-lt';
    }
    return 'bg-yellow-lt';
  }
}
