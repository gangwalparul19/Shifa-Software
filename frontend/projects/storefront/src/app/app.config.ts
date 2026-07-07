import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import { provideCore } from 'core';

import { routes } from './app.routes';
import { environment } from '../environments/environment';
import { provideI18n } from './shared/i18n';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes),
    provideCore(environment.apiBaseUrl),
    // Runtime EN/HI translations (ngx-translate) + persisted-language initializer.
    provideI18n(),
    // Register the Angular service worker only in production builds so it never
    // caches during development. `environment.production` is false in the
    // development configuration (via file replacement) and true in prod.
    provideServiceWorker('ngsw-worker.js', {
      enabled: environment.production,
      registrationStrategy: 'registerWhenStable:30000',
    }),
  ],
};
