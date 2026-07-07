import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { API_BASE_URL } from './tokens/api-base-url.token';
import { authInterceptor } from './auth/auth.interceptor';

/**
 * Wires the `core` library into an application: registers the API base URL,
 * and configures `HttpClient` with the JWT {@link authInterceptor}.
 *
 * Usage in an app's `appConfig`:
 * ```ts
 * providers: [provideCore(environment.apiBaseUrl)]
 * ```
 */
export function provideCore(apiBaseUrl: string): EnvironmentProviders {
  return makeEnvironmentProviders([
    { provide: API_BASE_URL, useValue: apiBaseUrl },
    provideHttpClient(withInterceptors([authInterceptor])),
  ]);
}
