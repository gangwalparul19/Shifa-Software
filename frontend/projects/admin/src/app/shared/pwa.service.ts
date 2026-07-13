import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { SwUpdate } from '@angular/service-worker';

/**
 * The (non-standard but widely supported) `beforeinstallprompt` event, captured
 * so we can offer an in-app "Install app" affordance instead of relying on the
 * browser's own prompt (FEATURE-ROADMAP §8.1).
 */
interface BeforeInstallPromptEvent extends Event {
  readonly platforms: string[];
  prompt(): Promise<void>;
  readonly userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/**
 * App-wide PWA state: connectivity (online/offline), installability + install
 * trigger, and service-worker update availability (FEATURE-ROADMAP §8.1).
 *
 * <p>Safe to use whether or not the service worker is active — {@link SwUpdate}
 * is a no-op when disabled (dev builds), and the install/connectivity pieces are
 * pure browser APIs. Everything is exposed as signals for template binding.
 */
@Injectable({ providedIn: 'root' })
export class PwaService {
  private readonly swUpdate = inject(SwUpdate);
  private readonly destroyRef = inject(DestroyRef);

  /** Whether the browser currently reports a network connection. */
  readonly online = signal(typeof navigator === 'undefined' ? true : navigator.onLine);

  /** True when the browser has offered an install prompt we can replay. */
  readonly installable = computed(() => this.deferredPrompt() !== null);

  /** True when a newer app version has been downloaded and is ready to activate. */
  readonly updateReady = signal(false);

  private readonly deferredPrompt = signal<BeforeInstallPromptEvent | null>(null);

  constructor() {
    if (typeof window !== 'undefined') {
      const onOnline = () => this.online.set(true);
      const onOffline = () => this.online.set(false);
      const onBeforeInstall = (e: Event) => {
        e.preventDefault();
        this.deferredPrompt.set(e as BeforeInstallPromptEvent);
      };
      const onInstalled = () => this.deferredPrompt.set(null);

      window.addEventListener('online', onOnline);
      window.addEventListener('offline', onOffline);
      window.addEventListener('beforeinstallprompt', onBeforeInstall);
      window.addEventListener('appinstalled', onInstalled);
      this.destroyRef.onDestroy(() => {
        window.removeEventListener('online', onOnline);
        window.removeEventListener('offline', onOffline);
        window.removeEventListener('beforeinstallprompt', onBeforeInstall);
        window.removeEventListener('appinstalled', onInstalled);
      });
    }

    if (this.swUpdate.isEnabled) {
      this.swUpdate.versionUpdates.subscribe((event) => {
        if (event.type === 'VERSION_READY') {
          this.updateReady.set(true);
        }
      });
    }
  }

  /** Shows the browser install prompt (no-op when not installable). */
  async promptInstall(): Promise<void> {
    const prompt = this.deferredPrompt();
    if (!prompt) {
      return;
    }
    await prompt.prompt();
    await prompt.userChoice;
    // A prompt can only be used once.
    this.deferredPrompt.set(null);
  }

  /** Activates a downloaded update and reloads into the new version. */
  async applyUpdate(): Promise<void> {
    if (!this.swUpdate.isEnabled) {
      return;
    }
    await this.swUpdate.activateUpdate();
    document.location.reload();
  }
}
