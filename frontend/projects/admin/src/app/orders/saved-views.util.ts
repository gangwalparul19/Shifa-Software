/**
 * Saved views (persisted filter sets) for the admin Orders page (Set A — A4).
 *
 * <p>A saved view captures the current search term, status / payment-status
 * filters, date window, and sort expression under a user-given name, persisted
 * in {@code localStorage} (per-browser). The store is seeded once with a couple
 * of useful defaults ("Awaiting approval", "COD pending", "RTO") that behave
 * like any other saved view — they can be applied, re-saved, or deleted.
 *
 * <p>Status values use the real {@link OrderStatus}/{@link PaymentStatus} enum
 * string values so an applied view maps 1:1 onto the Orders filter controls and
 * the {@code GET /api/admin/orders} query the page already issues.
 */
import { OrderStatus, PaymentStatus } from 'core';

/** A single persisted Orders filter preset. */
export interface SavedView {
  /** Stable id (used for tracking + deletion). */
  id: string;
  /** Human label shown on the view chip. */
  name: string;
  /** Free-text search term. */
  q: string;
  /**
   * Legacy raw order-status filter value (empty = all). Retained for backward
   * compatibility with previously saved views; new views use {@link statusGroup}.
   */
  status: string;
  /**
   * Grouped order-status filter key (e.g. {@code PENDING_APPROVAL}); empty = all.
   * Preferred over {@link status}. Optional so older persisted views still parse.
   */
  statusGroup?: string;
  /** Payment-status filter value (empty = all). */
  paymentStatus: string;
  /** Inclusive from date, yyyy-MM-dd (empty = none). */
  from: string;
  /** Inclusive to date, yyyy-MM-dd (empty = none). */
  to: string;
  /** Sort expression `field,dir` (empty = page default). */
  sort: string;
}

const STORAGE_KEY = 'admin.orders.savedViews';

/** The defaults seeded on first use — sensible, high-traffic Orders slices. */
const DEFAULT_VIEWS: SavedView[] = [
  {
    id: 'seed-awaiting-approval',
    name: 'Awaiting approval',
    q: '',
    status: OrderStatus.PENDING_ADMIN_APPROVAL,
    paymentStatus: '',
    from: '',
    to: '',
    sort: 'createdAt,desc',
  },
  {
    id: 'seed-cod-pending',
    name: 'COD pending',
    q: '',
    status: '',
    paymentStatus: PaymentStatus.COD,
    from: '',
    to: '',
    sort: 'createdAt,desc',
  },
  {
    id: 'seed-rto',
    name: 'RTO',
    q: '',
    status: OrderStatus.RTO,
    paymentStatus: '',
    from: '',
    to: '',
    sort: 'createdAt,desc',
  },
];

/** True when {@code localStorage} is usable (guards SSR / privacy modes). */
function storageAvailable(): boolean {
  try {
    return typeof localStorage !== 'undefined';
  } catch {
    return false;
  }
}

/**
 * Loads the saved views. On first access (no stored key) the store is seeded
 * with {@link DEFAULT_VIEWS} and persisted, so the defaults are editable and
 * deletable like any user-created view.
 */
export function loadSavedViews(): SavedView[] {
  if (!storageAvailable()) {
    return [...DEFAULT_VIEWS];
  }
  const raw = localStorage.getItem(STORAGE_KEY);
  if (raw === null) {
    persistSavedViews(DEFAULT_VIEWS);
    return [...DEFAULT_VIEWS];
  }
  try {
    const parsed = JSON.parse(raw) as SavedView[];
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

/** Persists the full list of saved views. */
export function persistSavedViews(views: SavedView[]): void {
  if (!storageAvailable()) {
    return;
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(views));
  } catch {
    /* quota / privacy-mode failures are non-fatal for this convenience feature */
  }
}

/** Generates a reasonably unique id for a newly saved view. */
export function newViewId(): string {
  return `view-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
}
