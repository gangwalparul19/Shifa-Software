import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, ProductReviews, ReviewInput, ReviewSubmission } from 'core';

/**
 * Storefront reviews data access (Phase C).
 *
 * <p>Public reads go to the catalog reviews endpoint (approved reviews +
 * aggregate); submission posts to the authenticated account reviews endpoint
 * (the bearer token is attached by the core auth interceptor).
 */
@Injectable({ providedIn: 'root' })
export class ReviewsService {
  private readonly api = inject(ApiClient);

  /** Approved reviews + aggregate for a product (public). */
  forProduct(productId: number): Observable<ProductReviews> {
    return this.api.get<ProductReviews>(`/api/catalog/products/${productId}/reviews`);
  }

  /** Submit (or update) the current customer's review; created PENDING moderation. */
  submit(input: ReviewInput): Observable<ReviewSubmission> {
    return this.api.post<ReviewSubmission>('/api/account/reviews', input);
  }
}
