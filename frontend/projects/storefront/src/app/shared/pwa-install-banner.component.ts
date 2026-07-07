import { Component, computed, inject, signal } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { PwaInstallService } from './pwa-install.service';

/** localStorage key remembering that the customer dismissed the install banner. */
const DISMISSED_KEY = 'shifa.pwaInstallDismissed.v1';

/**
 * Dismissible PWA install-prompt banner (Phase 1.3 PWA polish).
 *
 * <p>Driven by {@link PwaInstallService}: appears at the bottom of the viewport
 * only when the browser has offered an installable prompt and the app isn't
 * already installed, and stays hidden once the customer installs or dismisses it
 * (the dismissal is remembered in {@code localStorage}). "Install" replays the
 * native prompt; the header also keeps its compact install button, so this is an
 * additional, friendlier affordance. The banner has no motion beyond a gentle
 * slide, suppressed under {@code prefers-reduced-motion}.
 */
@Component({
  selector: 'sf-pwa-install-banner',
  imports: [TranslatePipe],
  template: `
    @if (visible()) {
      <div class="pwa-banner" role="region" [attr.aria-label]="'pwa.installTitle' | translate">
        <span class="pwa-icon" aria-hidden="true">🌿</span>
        <div class="pwa-copy">
          <strong>{{ 'pwa.installTitle' | translate }}</strong>
          <span>{{ 'pwa.installBody' | translate }}</span>
        </div>
        <div class="pwa-actions">
          <button type="button" class="btn btn-primary pwa-install" (click)="install()">
            {{ 'nav.installApp' | translate }}
          </button>
          <button
            type="button"
            class="pwa-dismiss"
            (click)="dismiss()"
            [attr.aria-label]="'common.close' | translate"
          >
            ✕
          </button>
        </div>
      </div>
    }
  `,
  styleUrl: './pwa-install-banner.component.css',
})
export class PwaInstallBannerComponent {
  private readonly pwa = inject(PwaInstallService);
  private readonly dismissed = signal(this.restoreDismissed());

  /** Banner shows only when installable, not installed and not yet dismissed. */
  protected readonly visible = computed(
    () => this.pwa.canInstall() && !this.pwa.installed() && !this.dismissed(),
  );

  install(): void {
    void this.pwa.promptInstall();
  }

  dismiss(): void {
    this.dismissed.set(true);
    try {
      localStorage.setItem(DISMISSED_KEY, '1');
    } catch {
      // Storage unavailable (private mode): banner stays dismissed in-memory.
    }
  }

  private restoreDismissed(): boolean {
    try {
      return localStorage.getItem(DISMISSED_KEY) === '1';
    } catch {
      return false;
    }
  }
}
