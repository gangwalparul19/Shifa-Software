import { Component, inject } from '@angular/core';
import { AppLanguage, LANGUAGE_LABELS, LanguageService } from './i18n';

/**
 * Header language switcher (EN | हिं) for the storefront (Phase F i18n).
 *
 * <p>Renders one chip per supported language, highlights the active one, and
 * switches + persists the choice via {@link LanguageService}. Uses
 * {@code aria-pressed} for accessibility so assistive tech announces the
 * current selection.
 */
@Component({
  selector: 'sf-language-switcher',
  template: `
    <div class="lang-switch" role="group" aria-label="Language">
      @for (lang of languages; track lang) {
        <button
          type="button"
          class="lang-chip"
          [class.active]="i18n.isActive(lang)"
          [attr.aria-pressed]="i18n.isActive(lang)"
          (click)="select(lang)"
        >
          {{ labels[lang] }}
        </button>
      }
    </div>
  `,
  styles: [
    `
      .lang-switch {
        display: inline-flex;
        align-items: center;
        border: 1px solid var(--line, #e7e2d6);
        border-radius: 999px;
        overflow: hidden;
        background: var(--white, #fff);
      }
      .lang-chip {
        border: none;
        background: transparent;
        font-family: inherit;
        font-size: 0.8rem;
        font-weight: 600;
        color: var(--muted, #6f7570);
        padding: 6px 12px;
        line-height: 1;
        transition:
          background 0.2s ease,
          color 0.2s ease;
      }
      .lang-chip + .lang-chip {
        border-left: 1px solid var(--line, #e7e2d6);
      }
      .lang-chip:hover {
        color: var(--herbal-green, #1f5d3f);
      }
      .lang-chip.active {
        background: var(--herbal-green, #1f5d3f);
        color: #fff;
      }
    `,
  ],
})
export class LanguageSwitcherComponent {
  protected readonly i18n = inject(LanguageService);
  protected readonly languages = this.i18n.languages;
  protected readonly labels = LANGUAGE_LABELS;

  select(lang: AppLanguage): void {
    this.i18n.use(lang);
  }
}
