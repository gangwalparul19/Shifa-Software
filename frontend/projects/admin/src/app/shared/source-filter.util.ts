/**
 * Shared helper for the Portal / Shopify / All order-source filter used across
 * the operational queues (Packing, Approval, Payments, Exception Center).
 *
 * "Portal" means every order NOT imported from Shopify (i.e. SALESPERSON / STORE
 * / STOREFRONT); "Shopify" means only Shopify-imported orders; "All" shows
 * everything. The default is "Portal" so the team's own orders lead and the
 * (often larger) Shopify flow doesn't bury them.
 */
export type SourceFilterMode = 'PORTAL' | 'SHOPIFY' | 'ALL';

/** The persisted default when the user hasn't chosen yet. */
export const DEFAULT_SOURCE_FILTER: SourceFilterMode = 'PORTAL';

/**
 * Reads the saved source-filter choice for a page from localStorage, falling
 * back to the Portal default. The {@code key} namespaces each page's choice.
 */
export function readSourceFilter(key: string): SourceFilterMode {
  if (typeof localStorage === 'undefined') {
    return DEFAULT_SOURCE_FILTER;
  }
  const saved = localStorage.getItem(key);
  return saved === 'PORTAL' || saved === 'SHOPIFY' || saved === 'ALL'
    ? saved
    : DEFAULT_SOURCE_FILTER;
}

/** Persists the source-filter choice for a page (best-effort). */
export function writeSourceFilter(key: string, mode: SourceFilterMode): void {
  try {
    localStorage.setItem(key, mode);
  } catch {
    /* ignore storage errors (private mode) */
  }
}

/**
 * Whether a row with the given {@code source} matches the active filter mode.
 * A row with no source (e.g. a non-order Exception Center insight) matches ALL
 * and PORTAL (it is treated as "not Shopify") but not the Shopify-only view.
 */
export function matchesSourceMode(
  source: string | null | undefined,
  mode: SourceFilterMode,
): boolean {
  if (mode === 'ALL') {
    return true;
  }
  const isShopify = source === 'SHOPIFY';
  return mode === 'SHOPIFY' ? isShopify : !isShopify;
}
