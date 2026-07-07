import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * Config-level smoke tests for the Storefront PWA wiring (Requirement 4.2) and
 * responsive viewport setup (Requirement 4.1). These run under the Node-based
 * Jest runner (the workspace's `.pbt.ts` runner) so they can read the project's
 * static config from disk. A regression that drops the manifest, the
 * service-worker config, or the install wiring is caught without a browser.
 *
 * Paths resolve from the workspace (frontend) root — the cwd when Jest runs.
 */
function readProjectFile(relativePath: string): string {
  return readFileSync(resolve('projects/storefront', relativePath), 'utf8');
}

describe('Storefront PWA configuration', () => {
  it('ships a web app manifest with brand name, colors, and icon sizes 72..512 (4.2)', () => {
    const manifest = JSON.parse(readProjectFile('public/manifest.webmanifest'));

    expect(manifest.name).toBe('Shifa Herbal Remedies');
    expect(manifest.short_name).toBeTruthy();
    expect(manifest.display).toBe('standalone');
    expect(manifest.start_url).toBe('/');
    expect(manifest.scope).toBe('/');
    // Herbal-green theme + cream background brand palette.
    expect(manifest.theme_color.toUpperCase()).toBe('#1F5D3F');
    expect(manifest.background_color.toUpperCase()).toBe('#FAF7F0');

    const sizes = new Set<string>(manifest.icons.map((i: { sizes: string }) => i.sizes));
    for (const s of ['72x72', '96x96', '128x128', '144x144', '152x152', '192x192', '384x384', '512x512']) {
      expect(sizes.has(s)).toBe(true);
    }
    // At least one maskable icon for adaptive launchers.
    expect(
      manifest.icons.some((i: { purpose?: string }) => (i.purpose ?? '').includes('maskable')),
    ).toBe(true);
  });

  it('provides an ngsw service-worker config with an app-shell asset group (4.2)', () => {
    const ngsw = JSON.parse(readProjectFile('ngsw-config.json'));
    expect(ngsw.index).toBe('/index.html');
    const groupNames = ngsw.assetGroups.map((g: { name: string }) => g.name);
    expect(groupNames).toContain('app');
  });

  it('links the manifest and sets a responsive viewport in index.html (4.1, 4.2)', () => {
    const indexHtml = readProjectFile('src/index.html');
    expect(indexHtml).toContain('rel="manifest"');
    expect(indexHtml).toContain('manifest.webmanifest');
    expect(indexHtml).toContain('width=device-width');
    expect(indexHtml).toContain('theme-color');
  });

  it('registers the service worker only in production builds (4.2)', () => {
    const appConfig = readProjectFile('src/app/app.config.ts');
    expect(appConfig).toContain('provideServiceWorker');
    expect(appConfig).toContain("'ngsw-worker.js'");
    // Enabled is gated on the production flag so dev never caches.
    expect(appConfig).toContain('enabled: environment.production');
  });

  it('defines responsive breakpoints for tablet and mobile viewports (4.1)', () => {
    const appCss = readProjectFile('src/app/app.css');
    expect(appCss).toContain('@media (max-width: 900px)');
    expect(appCss).toContain('@media (max-width: 768px)');
    expect(appCss).toContain('@media (max-width: 480px)');
  });

  it('handles beforeinstallprompt and exposes an install affordance (4.3)', () => {
    const service = readProjectFile('src/app/shared/pwa-install.service.ts');
    // The service defers the browser prompt and tracks installability/installed.
    expect(service).toContain("addEventListener('beforeinstallprompt'");
    expect(service).toContain("addEventListener('appinstalled'");
    expect(service).toContain('event.preventDefault()');
    expect(service).toContain('canInstall');
    expect(service).toContain('promptInstall');

    // The header renders an install button, gated so it hides when unavailable
    // or already installed, wired to the prompt trigger.
    const template = readProjectFile('src/app/app.html');
    expect(template).toContain('pwa.canInstall()');
    expect(template).toContain('!pwa.installed()');
    expect(template).toContain('installApp()');

    const shell = readProjectFile('src/app/app.ts');
    expect(shell).toContain('PwaInstallService');
    expect(shell).toContain('promptInstall');
  });
});
