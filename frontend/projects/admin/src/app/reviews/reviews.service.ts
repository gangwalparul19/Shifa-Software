import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { AdminReview, ApiClient } from 'core';

/** Status filter values accepted by the moderation queue. */
export type ReviewStatusFilter = 'PENDING' | 'APPROVED' | 'REJECTED' | 'ALL';

/**
 * Data access for admin review moderation ({@code /api/admin/reviews}, Phase C).
 * Calls go through the shared {@link ApiClient}; the auth interceptor attaches
 * the bearer token and the backend enforces the ADMIN role.
 */
@Injectable({ providedIn: 'root' })
export class ReviewsService {
  private readonly api = inject(ApiClient);

  /** The moderation queue, filtered by status (default PENDING). */
  queue(status: ReviewStatusFilter = 'PENDING'): Observable<AdminReview[]> {
    return this.api.get<AdminReview[]>('/api/admin/reviews', { params: { status } });
  }

  /** Approve a review → APPROVED (visible on the storefront). */
  approve(id: number): Observable<AdminReview> {
    return this.api.post<AdminReview>(`/api/admin/reviews/${id}/approve`);
  }

  /** Reject a review → REJECTED. */
  reject(id: number): Observable<AdminReview> {
    return this.api.post<AdminReview>(`/api/admin/reviews/${id}/reject`);
  }
}
