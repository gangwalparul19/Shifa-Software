import { Injectable, signal } from '@angular/core';

/**
 * The `beforeinstallprompt` event is not yet in the standard DOM lib typings,
 * so we describe the shape we rely on here.
 */
interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[];
  prompt(): Promise<void>;
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }>;
}

/**
 * Tracks PWA installability for the Storefront.
 *
 * Browsers that support installation fire a `beforeinstallprompt` event; we
 * capture it (preventing the default mini-infobar) and expose {@link canInstall}
 * so the UI can present an explicit "Install app" affordance (Requirement 4.3).
 * The deferred event is replayed via {@link promptInstall} when the customer
 * chooses to install. Once installed (or when running in standalone/installed
 * mode) the option is hidden.
 */
@Injectable({ providedIn: 'root' })
export class PwaInstallService {
  /** True when the browser has offered an installable prompt we can replay. */
  readonly canInstall = signal(false);
  /** True once the app has been installed (or is already running installed). */
  readonly installed = signal(false);

  private deferredPrompt: BeforeInstallPromptEvent | null = null;

  constructor() {
    // Guard against non-browser (SSR/test) environments.
    if (typeof window === 'undefined') {
      return;
    }

    if (this.isRunningStandalone()) {
      this.installed.set(true);
    }

    window.addEventListener('beforeinstallprompt', (event: Event) => {
      // Stop the browser's default mini-infobar so we can show our own control.
      event.preventDefault();
      this.deferredPrompt = event as BeforeInstallPromptEvent;
      this.canInstall.set(true);
    });

    window.addEventListener('appinstalled', () => {
      this.deferredPrompt = null;
      this.canInstall.set(false);
      this.installed.set(true);
    });
  }

  /**
   * Triggers the native install prompt if one is available.
   * Returns the user's choice, or `null` when no prompt was pending.
   */
  async promptInstall(): Promise<'accepted' | 'dismissed' | null> {
    const promptEvent = this.deferredPrompt;
    if (!promptEvent) {
      return null;
    }
    await promptEvent.prompt();
    const choice = await promptEvent.userChoice;
    // A prompt can only be used once; clear it either way.
    this.deferredPrompt = null;
    this.canInstall.set(false);
    if (choice.outcome === 'accepted') {
      this.installed.set(true);
    }
    return choice.outcome;
  }

  /** Detects whether the app is already running as an installed PWA. */
  private isRunningStandalone(): boolean {
    const standaloneMatch =
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(display-mode: standalone)').matches;
    // iOS Safari exposes navigator.standalone instead of display-mode.
    const iosStandalone = (window.navigator as { standalone?: boolean }).standalone === true;
    return standaloneMatch || iosStandalone;
  }
}
