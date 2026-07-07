import {
  Component,
  HostListener,
  OnDestroy,
  OnInit,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { AdminEventsService } from '../dashboard/admin-events.service';
import { StaffNotificationsService } from './staff-notifications.service';
import { AdminNotificationItem } from './notifications.model';
import { relativeTime, severityColor, severityIcon } from './notifications.util';

/** How often (ms) to re-poll the unread count. */
const POLL_MS = 60_000;
/** How many recent notifications to show in the dropdown. */
const DROPDOWN_LIMIT = 8;

/**
 * Top-bar notifications bell (Set B — Feature 3, compact).
 *
 * <p>Shows an unread-count badge (polled every ~60s and bumped whenever the
 * live SSE feed pushes a new notification so it feels real-time) and, on click,
 * a dropdown of recent notifications. Clicking a notification marks it read;
 * "Mark all read" clears the count and "View all" links to the full page. The
 * dropdown closes on Escape or an outside click and carries appropriate ARIA.
 *
 * <p>Rendered for every authenticated staff role. It reads the staff-facing
 * {@code /api/notifications} endpoint, so it shows the notifications addressed
 * to the current user's role or user id (plus legacy admin broadcasts for
 * admins) rather than only admin broadcasts (Req 13.4).
 */
@Component({
  selector: 'admin-notification-bell',
  standalone: true,
  imports: [RouterLink],
  templateUrl: './notification-bell.component.html',
  styleUrl: './notification-bell.component.css',
})
export class NotificationBellComponent implements OnInit, OnDestroy {
  private readonly service = inject(StaffNotificationsService);
  private readonly events = inject(AdminEventsService);

  protected readonly relativeTime = relativeTime;
  protected readonly severityColor = severityColor;
  protected readonly severityIcon = severityIcon;

  protected readonly open = signal(false);
  protected readonly unread = signal(0);
  protected readonly recent = signal<AdminNotificationItem[]>([]);
  protected readonly loading = signal(false);

  private pollTimer: ReturnType<typeof setInterval> | null = null;
  /** Length of the SSE feed we last accounted for, to detect live bumps. */
  private lastFeedLength = 0;

  constructor() {
    // Bump the unread count when the live SSE feed grows so the badge feels
    // live between polls (Set B — Feature 3).
    effect(() => {
      const len = this.events.notifications().length;
      if (len > this.lastFeedLength) {
        const delta = len - this.lastFeedLength;
        this.unread.update((n) => n + delta);
      }
      this.lastFeedLength = len;
    });
  }

  ngOnInit(): void {
    this.refreshCount();
    this.pollTimer = setInterval(() => this.refreshCount(), POLL_MS);
  }

  ngOnDestroy(): void {
    if (this.pollTimer) {
      clearInterval(this.pollTimer);
    }
  }

  /** Fetches the authoritative unread count from the server. */
  refreshCount(): void {
    this.service.unreadCount().subscribe({
      next: (res) => this.unread.set(res.unreadCount ?? 0),
      error: () => {
        /* transient; keep the last known count */
      },
    });
  }

  toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next) {
      this.loadRecent();
    }
  }

  close(): void {
    this.open.set(false);
  }

  private loadRecent(): void {
    this.loading.set(true);
    this.service.page({ page: 0, size: DROPDOWN_LIMIT, sort: 'createdAt,desc' }).subscribe({
      next: (res) => {
        this.recent.set(res.content);
        this.loading.set(false);
        // Reconcile the badge with the server on open.
        this.refreshCount();
      },
      error: () => this.loading.set(false),
    });
  }

  markRead(item: AdminNotificationItem, event: Event): void {
    event.stopPropagation();
    if (item.read) {
      return;
    }
    this.service.markRead(item.id).subscribe({
      next: () => {
        this.recent.update((list) =>
          list.map((n) => (n.id === item.id ? { ...n, read: true } : n)),
        );
        this.unread.update((n) => Math.max(0, n - 1));
      },
    });
  }

  markAllRead(event: Event): void {
    event.stopPropagation();
    // The staff endpoint has no bulk read-all, so mark each visible unread
    // notification read individually.
    const unreadIds = this.recent().filter((n) => !n.read).map((n) => n.id);
    this.service.markManyRead(unreadIds).subscribe({
      next: () => {
        this.recent.update((list) => list.map((n) => ({ ...n, read: true })));
        this.unread.set(0);
        // Reconcile with the server (there may be more unread beyond the dropdown).
        this.refreshCount();
      },
    });
  }

  /** Escape closes the dropdown for keyboard users. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open()) {
      this.close();
    }
  }
}
