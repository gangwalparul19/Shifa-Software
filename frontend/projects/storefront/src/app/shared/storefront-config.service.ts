import { Injectable, computed, inject, signal } from '@angular/core';
import { ApiClient } from 'core';

/**
 * Public storefront display configuration returned by
 * {@code GET /api/storefront/config} — the minimal, non-sensitive subset of the
 * company settings the storefront needs for its engagement features.
 */
export interface StorefrontConfig {
  storeName: string | null;
  whatsappNumber: string | null;
  supportEmail: string | null;
}

/** Built-in defaults used until the public config loads (or if it fails). */
export const DEFAULT_STORE_NAME = 'Shifa Herbal Remedies';
/** Fallback WhatsApp number (matches the footer contact) — digits only, with country code. */
export const DEFAULT_WHATSAPP_NUMBER = '919302590767';
export const DEFAULT_SUPPORT_EMAIL = 'care@shifaherbal.in';

/**
 * Loads and caches the public storefront configuration (store name, WhatsApp
 * number, support email) from the backend.
 *
 * <p>The values feed the WhatsApp order handoff and support links. The service
 * fetches once on first use and exposes reactive signals with sensible built-in
 * fallbacks so the UI always has a usable number/email even before the request
 * resolves or if it fails (e.g. offline). The raw phone from settings is
 * normalized to a wa.me-safe, digits-only form via {@link whatsappDigits}.
 */
@Injectable({ providedIn: 'root' })
export class StorefrontConfigService {
  private readonly api = inject(ApiClient);
  private readonly config = signal<StorefrontConfig | null>(null);
  private loaded = false;

  /** Public store/brand name (falls back to the Shifa brand name). */
  readonly storeName = computed(() => this.config()?.storeName?.trim() || DEFAULT_STORE_NAME);

  /** Support email (falls back to the built-in care address). */
  readonly supportEmail = computed(
    () => this.config()?.supportEmail?.trim() || DEFAULT_SUPPORT_EMAIL,
  );

  /** WhatsApp number as wa.me-ready digits (country code, no symbols). */
  readonly whatsappNumber = computed(() => {
    const raw = this.config()?.whatsappNumber;
    const digits = whatsappDigits(raw);
    return digits || DEFAULT_WHATSAPP_NUMBER;
  });

  constructor() {
    this.load();
  }

  /** Fetches the public config once; safe to call repeatedly. */
  load(): void {
    if (this.loaded) {
      return;
    }
    this.loaded = true;
    this.api.get<StorefrontConfig>('/api/storefront/config').subscribe({
      next: (cfg) => this.config.set(cfg),
      error: () => this.config.set(null),
    });
  }
}

/**
 * Normalizes a human phone string to the digits wa.me expects: strips spaces,
 * "+", dashes and brackets; a bare 10-digit Indian mobile is prefixed with the
 * "91" country code. Returns an empty string when there are no usable digits.
 */
export function whatsappDigits(raw: string | null | undefined): string {
  if (!raw) {
    return '';
  }
  const digits = raw.replace(/\D+/g, '');
  if (digits.length === 10) {
    return `91${digits}`;
  }
  return digits;
}
