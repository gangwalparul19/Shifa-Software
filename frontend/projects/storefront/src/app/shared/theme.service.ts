import { DOCUMENT } from '@angular/common';
import { Injectable, effect, inject, signal } from '@angular/core';

/** The two supported storefront colour themes. */
export type Theme = 'light' | 'dark';

/** localStorage key holding the user's explicit theme choice. */
const STORAGE_KEY = 'shifa.theme';

/** <meta name="theme-color"> values per theme (matches the CSS --bg / brand). */
const META_COLORS: Record<Theme, string> = {
  light: '#1F5D3F',
  dark: '#111310',
};

/**
 * Signal-based light/dark theme controller for the storefront.
 *
 * <p>Initial theme resolves from (1) the user's saved choice in
 * {@code localStorage('shifa.theme')}, else (2) the OS
 * {@code prefers-color-scheme: dark} preference, else (3) light. The resolved
 * theme is reflected onto {@code <html data-theme="…">} so the global CSS
 * token overrides in {@code styles.css} take effect, and the
 * {@code <meta name="theme-color">} tag is kept in sync for the mobile browser
 * chrome. The user's explicit {@link #toggle} choice is persisted.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);

  /** The active theme; drives the whole app via the {@code data-theme} attribute. */
  readonly theme = signal<Theme>('light');

  constructor() {
    this.theme.set(this.resolveInitial());
    // Reflect every theme change onto the DOM (runs immediately + on toggle).
    effect(() => this.apply(this.theme()));
  }

  /** Flips light↔dark and persists the explicit choice. */
  toggle(): void {
    const next: Theme = this.theme() === 'dark' ? 'light' : 'dark';
    this.theme.set(next);
    try {
      this.doc.defaultView?.localStorage?.setItem(STORAGE_KEY, next);
    } catch {
      /* storage may be unavailable (private mode) — theme still applies for the session */
    }
  }

  /** True when the dark theme is active (handy for template bindings). */
  isDark(): boolean {
    return this.theme() === 'dark';
  }

  private resolveInitial(): Theme {
    try {
      const stored = this.doc.defaultView?.localStorage?.getItem(STORAGE_KEY);
      if (stored === 'light' || stored === 'dark') {
        return stored;
      }
    } catch {
      /* ignore and fall through to system preference */
    }
    const prefersDark = this.doc.defaultView?.matchMedia?.('(prefers-color-scheme: dark)').matches;
    return prefersDark ? 'dark' : 'light';
  }

  private apply(theme: Theme): void {
    const root = this.doc.documentElement;
    root.setAttribute('data-theme', theme);
    const meta = this.doc.querySelector('meta[name="theme-color"]');
    meta?.setAttribute('content', META_COLORS[theme]);
  }
}
