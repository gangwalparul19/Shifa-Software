import { Money } from './money.model';

/** Storefront visibility flag controlling catalog exposure (backend Req 6.4). */
export enum ProductVisibility {
  PUBLISHED = 'PUBLISHED',
  HIDDEN = 'HIDDEN',
}

/** An image belonging to a product; `published` drives placeholder logic (Req 1.4). */
export interface ProductImage {
  id: number;
  /** OCI Object Storage key, or a resolvable URL when hydrated for display. */
  objectKey: string;
  published: boolean;
  sortOrder: number;
}

/**
 * Derived stock/availability status for a product (backend: Catalog & Discovery).
 * Mirrors the backend {@code StockStatus} enum.
 */
export enum StockStatus {
  IN_STOCK = 'IN_STOCK',
  LOW_STOCK = 'LOW_STOCK',
  OUT_OF_STOCK = 'OUT_OF_STOCK',
}

/**
 * Lightweight category reference embedded in a {@link Product} (id/slug/name).
 * Mirrors the backend {@code CategoryRef}.
 */
export interface CategoryRef {
  id: number;
  slug: string;
  name: string;
}

/** A product category / collection. Mirrors the backend {@code CategoryResponse}. */
export interface Category {
  id: number;
  name: string;
  slug: string;
  description?: string;
  sortOrder: number;
  active: boolean;
}

/** Mirrors the backend Product DTO (`products` table). */
export interface Product {
  id: number;
  sku: string;
  name: string;
  description?: string;
  mrp: Money;
  salePrice: Money;
  /** Optional HSN code, surfaced on GST tax invoices (backend: GST feature). */
  hsnCode?: string;
  /**
   * Optional per-product GST rate percent (DECIMAL(5,2) as a string, e.g.
   * "12.00"); null/undefined falls back to the settings default rate.
   */
  gstRate?: string | null;
  visibility: ProductVisibility;
  /** Category the product belongs to, or undefined when uncategorised (Catalog & Discovery). */
  category?: CategoryRef;
  /** Derived availability status (Catalog & Discovery). */
  stockStatus?: StockStatus;
  /** On-hand units; only meaningful when {@link trackInventory} is true. */
  stockQuantity?: number;
  /** Whether the product tracks inventory (else it always reads in-stock). */
  trackInventory?: boolean;
  /** Whether the product is part of the featured collection. */
  featured?: boolean;
  images?: ProductImage[];
  createdAt?: string;
  updatedAt?: string;
  /**
   * Average of APPROVED review ratings (Phase C), or null/undefined when the
   * product has no approved reviews. Lets cards/detail show stars without a
   * second call.
   */
  averageRating?: number | null;
  /** Count of APPROVED reviews contributing to {@link averageRating}. */
  reviewCount?: number;
}
