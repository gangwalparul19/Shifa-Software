/**
 * Product reviews & ratings models (Phase C), mirroring the backend review DTOs.
 */

/** Moderation lifecycle of a review (mirrors backend {@code ReviewStatus}). */
export enum ReviewStatus {
  PENDING = 'PENDING',
  APPROVED = 'APPROVED',
  REJECTED = 'REJECTED',
}

/** A single approved review shown on the public product page. */
export interface ProductReview {
  id: number;
  authorName: string;
  rating: number;
  title?: string | null;
  body?: string | null;
  verified: boolean;
  createdAt?: string;
}

/**
 * Public reviews payload for a product ({@code GET /api/catalog/products/{id}/reviews}):
 * approved reviews plus the aggregate (average + count) and a per-star breakdown.
 */
export interface ProductReviews {
  productId: number;
  averageRating?: number | null;
  reviewCount: number;
  /** Map of star (1..5) → count, serialized as an object with string keys. */
  breakdown: Record<string, number>;
  reviews: ProductReview[];
}

/** Payload to submit (or update) a customer review ({@code POST /api/account/reviews}). */
export interface ReviewInput {
  productId: number;
  rating: number;
  title?: string;
  body?: string;
}

/** The result of submitting a review (confirms it is PENDING moderation). */
export interface ReviewSubmission {
  id: number;
  productId: number;
  rating: number;
  title?: string | null;
  body?: string | null;
  verified: boolean;
  status: ReviewStatus;
}

/** A review as seen in the admin moderation queue (full context). */
export interface AdminReview {
  id: number;
  productId: number;
  productName?: string | null;
  userId?: number | null;
  authorName: string;
  rating: number;
  title?: string | null;
  body?: string | null;
  verified: boolean;
  status: ReviewStatus;
  createdAt?: string;
  moderatedAt?: string | null;
  moderatedBy?: number | null;
}
