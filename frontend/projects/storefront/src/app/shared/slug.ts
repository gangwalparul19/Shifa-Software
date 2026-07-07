/**
 * Cosmetic URL slug helpers for SEO-friendly product links.
 *
 * <p>Product URLs are {@code /products/:id/:slug} where {@code id} is
 * authoritative for lookup and {@code slug} is a human-/search-engine-readable
 * rendering of the product name (e.g. {@code /products/42/ashwagandha-capsules}).
 * The slug is purely cosmetic — the id alone still resolves the product, so old
 * {@code /products/:id} links keep working.
 */

/**
 * Derives a URL-safe slug from a product name: lowercased, non-alphanumeric runs
 * collapsed to single hyphens, with leading/trailing hyphens trimmed. Returns an
 * empty string for a blank/undefined name.
 */
export function slugify(name: string | null | undefined): string {
  return (name ?? '')
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

/**
 * Builds the router commands for a product link, appending a cosmetic slug when
 * one can be derived. Falls back to {@code ['/products', id]} when the name is
 * empty so the link is always valid.
 */
export function productLink(id: number, name?: string | null): (string | number)[] {
  const slug = slugify(name);
  return slug ? ['/products', id, slug] : ['/products', id];
}
