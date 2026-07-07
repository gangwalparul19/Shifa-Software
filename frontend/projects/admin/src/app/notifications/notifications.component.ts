import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { Subject, takeUntil } from 'rxjs';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { PaginationComponent } from '../shared/pagination.component';
import { ToastService } from '../shared/toast.service';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { NotificationsService } from './notifications.service';
import { AdminNotificationItem } from './notifications.model';
import { relativeTime, severityColor, severityIcon } from './notifications.util';

const TABLE_KEY = 'notifications';

/**
 * Admin Notifications center (Set B — Feature 3, full page).
 *
 * <p>Lists persisted notifications with unread-only and type filters, paging,
 * and mark-read / mark-all-read actions. Complements the compact top-bar bell
 * dropdown which links here via "View all".
 */
@Component({
  selector: 'admin-notifications',
  imports: [
    ReactiveFormsModule,
    RouterLink,
    PageHeaderComponent,
    StatePanelComponent,
    PaginationComponent,
  ],
  templateUrl: './notifications.component.html',
  styleUrl: './notifications.component.css',
})
export class NotificationsComponent implements OnInit, OnDestroy {
  private readonly service = inject(NotificationsService);
  private readonly toasts = inject(ToastService);

  protected readonly relativeTime = relativeTime;
  protected readonly severityColor = severityColor;
  protected readonly severityIcon = severityIcon;

  protected readonly items = signal<AdminNotificationItem[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly busy = signal(false);
  /** Distinct notification types seen so far, for the type filter dropdown. */
  protected readonly types = signal<string[]>([]);

  // --- Paging -------------------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize(TABLE_KEY, 20));
  protected readonly totalPages = signal(0);
  protected readonly totalElements = signal(0);

  // --- Filters ------------------------------------------------------------
  protected readonly filters = new FormGroup({
    unreadOnly: new FormControl<boolean>(false, { nonNullable: true }),
    type: new FormControl<string>('', { nonNullable: true }),
  });

  private readonly destroy$ = new Subject<void>();

  ngOnInit(): void {
    this.load();
    this.filters.valueChanges.pipe(takeUntil(this.destroy$)).subscribe(() => this.resetAndLoad());
  }

  ngOnDestroy(): void {
    this.destroy$.next();
    this.destroy$.complete();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    const f = this.filters.getRawValue();
    this.service
      .page({
        unreadOnly: f.unreadOnly,
        type: f.type || null,
        page: this.page(),
        size: this.size(),
        sort: 'createdAt,desc',
      })
      .subscribe({
        next: (res) => {
          this.items.set(res.content);
          this.totalPages.set(res.totalPages);
          this.totalElements.set(res.totalElements);
          this.mergeTypes(res.content);
          this.loading.set(false);
        },
        error: () => {
          this.loadError.set('Could not load notifications. Please try again.');
          this.loading.set(false);
        },
      });
  }

  private mergeTypes(rows: AdminNotificationItem[]): void {
    const set = new Set(this.types());
    rows.forEach((r) => r.type && set.add(r.type));
    this.types.set([...set].sort());
  }

  private resetAndLoad(): void {
    this.page.set(0);
    this.load();
  }

  goToPage(page: number): void {
    this.page.set(page);
    this.load();
  }

  setSize(size: number): void {
    this.size.set(size);
    writePageSize(TABLE_KEY, size);
    this.resetAndLoad();
  }

  hasFilters(): boolean {
    const f = this.filters.getRawValue();
    return f.unreadOnly || !!f.type;
  }

  markRead(item: AdminNotificationItem): void {
    if (item.read) {
      return;
    }
    this.service.markRead(item.id).subscribe({
      next: () => {
        this.items.update((list) =>
          list.map((n) => (n.id === item.id ? { ...n, read: true } : n)),
        );
        // If we're viewing unread-only, drop it from the current view.
        if (this.filters.getRawValue().unreadOnly) {
          this.items.update((list) => list.filter((n) => n.id !== item.id));
        }
      },
      error: () => this.toasts.error('Could not mark the notification read.'),
    });
  }

  markAllRead(): void {
    if (this.busy()) {
      return;
    }
    this.busy.set(true);
    this.service.markAllRead().subscribe({
      next: () => {
        this.busy.set(false);
        this.toasts.success('All notifications marked read.');
        this.load();
      },
      error: () => {
        this.busy.set(false);
        this.toasts.error('Could not mark all read.');
      },
    });
  }

  humanizeType(type: string): string {
    return type
      .replaceAll('_', ' ')
      .toLowerCase()
      .replace(/\b\w/g, (c) => c.toUpperCase());
  }
}
