import { DOCUMENT } from '@angular/common';
import { Injectable, inject } from '@angular/core';
import { Meta, Title } from '@angular/platform-browser';

/** Brand defaults applied when a page doesn't override a given field. */
const SITE_NAME = 'Shifa Herbal Remedies';
const DEFAULT_DESCRIPTION =
  'Shifa Herbal Remedies — authentic Ayurvedic and herbal wellness products. ' +
  '100% natural, ethically sourced, delivered across India.';

/** Options accepted by {@link SeoService.setPage}. */
export interface SeoPage {
  /** Page title; the brand name is appended automatically unless it's the brand page. */
  title: string;
  /** Meta / Open Graph description. Falls back to the brand description. */
  description?: string;
  /** Absolute or root-relative image URL for social cards. */
  image?: string;
  /** Canonical URL; defaults to the current document location. */
  url?: string;
  /** Open Graph type, e.g. 'website' (default) or 'product'. */
  type?: string;
  /** When true the title is used verbatim (no " — Shifa …" suffix). */
  brand?: boolean;
}

/** The id used for the single JSON-LD structured-data script we manage. */
const JSON_LD_ID = 'sf-jsonld';

/**
 * Deployment-safe SEO helper (no SSR/prerender): a thin wrapper over Angular's
 * {@link Title} and {@link Meta} services that keeps the document {@code <title>},
 * description, Open Graph and Twitter card tags in sync as the SPA navigates.
 *
 * <p>Also manages a single {@code <script type="application/ld+json">} block for
 * schema.org structured data (e.g. a product's {@code Product}/{@code Offer}),
 * added on page enter and removed on leave so stale data never lingers.
 */
@Injectable({ providedIn: 'root' })
export class SeoService {
  private readonly title = inject(Title);
  private readonly meta = inject(Meta);
  private readonly doc = inject(DOCUMENT);

  /**
   * Sets the page title, description, Open Graph and Twitter card tags. Missing
   * fields fall back to sensible brand defaults; the URL defaults to the current
   * location so it's always populated even without SSR.
   */
  setPage(page: SeoPage): void {
    const description = page.description?.trim() || DEFAULT_DESCRIPTION;
    const fullTitle = page.brand ? page.title : `${page.title} — ${SITE_NAME}`;
    const url = page.url ?? this.currentUrl();
    const image = this.absolute(page.image);
    const type = page.type ?? 'website';

    this.title.setTitle(fullTitle);
    this.meta.updateTag({ name: 'description', content: description });

    // Open Graph
    this.meta.updateTag({ property: 'og:site_name', content: SITE_NAME });
    this.meta.updateTag({ property: 'og:title', content: fullTitle });
    this.meta.updateTag({ property: 'og:description', content: description });
    this.meta.updateTag({ property: 'og:type', content: type });
    this.meta.updateTag({ property: 'og:url', content: url });
    if (image) {
      this.meta.updateTag({ property: 'og:image', content: image });
    } else {
      this.meta.removeTag("property='og:image'");
    }

    // Twitter card
    this.meta.updateTag({
      name: 'twitter:card',
      content: image ? 'summary_large_image' : 'summary',
    });
    this.meta.updateTag({ name: 'twitter:title', content: fullTitle });
    this.meta.updateTag({ name: 'twitter:description', content: description });
    if (image) {
      this.meta.updateTag({ name: 'twitter:image', content: image });
    } else {
      this.meta.removeTag("name='twitter:image'");
    }
  }

  /**
   * Injects (or replaces) the managed JSON-LD structured-data script in the
   * document head. Passing {@code null} removes it. Guards against duplicates by
   * reusing the single {@code #sf-jsonld} script element.
   */
  setJsonLd(data: Record<string, unknown> | null): void {
    const head = this.doc.head;
    if (!head) {
      return;
    }
    let script = this.doc.getElementById(JSON_LD_ID) as HTMLScriptElement | null;
    if (!data) {
      if (script) {
        script.remove();
      }
      return;
    }
    if (!script) {
      script = this.doc.createElement('script');
      script.id = JSON_LD_ID;
      script.type = 'application/ld+json';
      head.appendChild(script);
    }
    script.textContent = JSON.stringify(data);
  }

  /** Removes the managed JSON-LD script (call on component destroy). */
  clearJsonLd(): void {
    this.setJsonLd(null);
  }

  /** The current absolute page URL, or the site base when unavailable. */
  private currentUrl(): string {
    try {
      return this.doc.location?.href ?? '';
    } catch {
      return '';
    }
  }

  /** Resolves a root-relative image path to an absolute URL for social cards. */
  private absolute(image?: string): string | undefined {
    const src = (image ?? '').trim();
    if (!src) {
      return undefined;
    }
    if (/^https?:\/\//i.test(src)) {
      return src;
    }
    const origin = this.doc.location?.origin ?? '';
    return origin ? `${origin}${src.startsWith('/') ? '' : '/'}${src}` : src;
  }
}
