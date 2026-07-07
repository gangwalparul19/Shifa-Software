import { DatePipe } from '@angular/common';
import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { AdminReview, ReviewStatus, starStates } from 'core';
import { ReviewStatusFilter, ReviewsService } from './reviews.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { ConfirmService } from '../shared/confirm.service';

interface Toast {
  kind: 'ok' | 'error';
  text: string;
}

/**
 * Admin review moderation page (Phase C).
 *
 * <p>A Tabler-styled queue of reviews with a status filter (PENDING by default;
 * APPROVED / REJECTED / ALL). Each row shows the product, author, rating stars,
 * title/body, a verified-purchase badge and the date, with Approve / Reject
 * actions on pending rows. On success the row is updated/removed and a toast is
 * shown.
 */
@Component({
  selector: 'admin-reviews',
  imports: [DatePipe, PageHeaderComponent, StatePanelComponent, DensityToggleComponent],
  templateUrl: './reviews.component.html',
  styleUrl: './reviews.component.css',
})
export class ReviewsComponent implements OnInit, OnDestroy {
  private readonly service = inject(ReviewsService);
  private readonly confirmService = inject(ConfirmService);

  protected readonly ReviewStatus = ReviewStatus;
  protected readonly filters: ReviewStatusFilter[] = ['PENDING', 'APPROVED', 'REJECTED', 'ALL'];

  protected readonly reviews = signal<AdminReview[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly filter = signal<ReviewStatusFilter>('PENDING');
  protected readonly acting = signal<number | null>(null);
  protected readonly toast = signal<Toast | null>(null);

  protected readonly pendingCount = computed(
    () => this.reviews().filter((r) => r.status === ReviewStatus.PENDING).length,
  );

  private toastTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
  }

  selectFilter(filter: ReviewStatusFilter): void {
    if (this.filter() === filter) {
      return;
    }
    this.filter.set(filter);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.queue(this.filter()).subscribe({
      next: (items) => {
        this.reviews.set(items);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load reviews. Please try again.');
        this.loading.set(false);
      },
    });
  }

  /** Five star states for a whole-number rating (reuses the shared core helper). */
  stars(rating: number): ReturnType<typeof starStates> {
    return starStates(rating);
  }

  approve(review: AdminReview): void {
    if (this.acting() !== null) {
      return;
    }
    this.acting.set(review.id);
    this.service.approve(review.id).subscribe({
      next: (updated) => {
        this.applyResult(updated);
        this.acting.set(null);
        this.showToast('ok', `Review by ${review.authorName} approved.`);
      },
      error: () => {
        this.acting.set(null);
        this.showToast('error', 'Could not approve the review.');
      },
    });
  }

  async reject(review: AdminReview): Promise<void> {
    if (this.acting() !== null) {
      return;
    }
    const confirmed = await this.confirmService.confirm({
      title: 'Reject review',
      message: `Reject the review by ${review.authorName}? It will not be shown on the storefront.`,
      confirmLabel: 'Reject',
      danger: true,
      icon: 'ti-x',
    });
    if (!confirmed) {
      return;
    }
    this.acting.set(review.id);
    this.service.reject(review.id).subscribe({
      next: (updated) => {
        this.applyResult(updated);
        this.acting.set(null);
        this.showToast('ok', `Review by ${review.authorName} rejected.`);
      },
      error: () => {
        this.acting.set(null);
        this.showToast('error', 'Could not reject the review.');
      },
    });
  }

  /**
   * Reflects a moderation result: when viewing a single status the row no longer
   * belongs, so it is removed; when viewing ALL the row is updated in place.
   */
  private applyResult(updated: AdminReview): void {
    if (this.filter() === 'ALL') {
      this.reviews.update((rows) => rows.map((r) => (r.id === updated.id ? updated : r)));
    } else {
      this.reviews.update((rows) => rows.filter((r) => r.id !== updated.id));
    }
  }

  private showToast(kind: Toast['kind'], text: string): void {
    this.toast.set({ kind, text });
    if (this.toastTimer) {
      clearTimeout(this.toastTimer);
    }
    this.toastTimer = setTimeout(() => this.toast.set(null), 4000);
  }
}
