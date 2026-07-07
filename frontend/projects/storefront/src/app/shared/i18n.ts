import { Injectable, Signal, computed, inject } from '@angular/core';
import { EnvironmentProviders, makeEnvironmentProviders, provideAppInitializer } from '@angular/core';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';

/** Supported storefront UI languages (English, Hindi). */
export const SUPPORTED_LANGUAGES = ['en', 'hi'] as const;
export type AppLanguage = (typeof SUPPORTED_LANGUAGES)[number];

/** Default language when no valid choice is persisted. */
export const DEFAULT_LANGUAGE: AppLanguage = 'en';

const STORAGE_KEY = 'shifa.lang';

/** Human labels for the language switcher chips. */
export const LANGUAGE_LABELS: Record<AppLanguage, string> = {
  en: 'EN',
  hi: 'हिं',
};

/** Narrows an arbitrary string to a supported language, or returns null. */
export function normalizeLanguage(value: string | null | undefined): AppLanguage | null {
  return value && (SUPPORTED_LANGUAGES as readonly string[]).includes(value)
    ? (value as AppLanguage)
    : null;
}

/**
 * Runtime language state for the storefront (Phase F i18n).
 *
 * <p>Thin adapter over ngx-translate's {@link TranslateService}: it initialises
 * the app to the persisted choice (localStorage) or the {@link DEFAULT_LANGUAGE},
 * exposes the {@link current} language as a reactive signal, and persists any
 * switch so the choice survives reloads. English is always the fallback so a
 * missing Hindi key degrades gracefully to English rather than showing the key.
 */
@Injectable({ providedIn: 'root' })
export class LanguageService {
  private readonly translate = inject(TranslateService);

  /** The active language as a reactive signal (defaults to English). */
  readonly current: Signal<AppLanguage> = computed(
    () => normalizeLanguage(this.translate.currentLang()) ?? DEFAULT_LANGUAGE,
  );

  /** The list of selectable languages for the switcher. */
  readonly languages = SUPPORTED_LANGUAGES;

  /** Loads the persisted (or default) language on app start. */
  init(): void {
    this.translate.setFallbackLang(DEFAULT_LANGUAGE);
    this.translate.addLangs([...SUPPORTED_LANGUAGES]);
    this.use(this.readStored() ?? DEFAULT_LANGUAGE);
  }

  /** Switches the active language and persists the choice. */
  use(lang: AppLanguage): void {
    this.translate.use(lang);
    this.persist(lang);
  }

  /** True when `lang` is the active language (drives switcher highlighting). */
  isActive(lang: AppLanguage): boolean {
    return this.current() === lang;
  }

  private readStored(): AppLanguage | null {
    try {
      return normalizeLanguage(localStorage.getItem(STORAGE_KEY));
    } catch {
      return null;
    }
  }

  private persist(lang: AppLanguage): void {
    try {
      localStorage.setItem(STORAGE_KEY, lang);
    } catch {
      // localStorage may be unavailable (private mode); language still applies in-memory.
    }
  }
}

/**
 * Wires ngx-translate into the storefront: an HTTP loader that reads
 * `/assets/i18n/{lang}.json`, English as the default/fallback language, and an
 * app initializer that applies the persisted language before first paint.
 */
export function provideI18n(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideTranslateService({
      fallbackLang: DEFAULT_LANGUAGE,
      lang: DEFAULT_LANGUAGE,
      loader: provideTranslateHttpLoader({
        prefix: '/assets/i18n/',
        suffix: '.json',
      }),
    }),
    provideAppInitializer(() => {
      inject(LanguageService).init();
    }),
  ]);
}
