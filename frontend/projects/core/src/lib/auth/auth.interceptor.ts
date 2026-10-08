import { inject } from '@angular/core';
import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError, filter, finalize, map, shareReplay, switchMap, take } from 'rxjs/operators';
import { AuthTokenStore } from './auth-token.store';
import { AuthEventsService } from './auth-events.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { isApiRequest, isPublicAuthRequest } from './auth-request-scope';

/** Minimal shape of the token-pair response from {@code /api/auth/refresh}. */
interface RefreshTokenResponse {
  accessToken: string;
  refreshToken: string;
}

/**
 * A single in-flight refresh shared across concurrent 401s (module-level so all
 * interceptor invocations coordinate). When several requests 401 at once — e.g.
 * a page issues a few calls after the 15-minute access token expires — they all
 * await the SAME refresh instead of each firing their own (which would race and
 * rotate the refresh token repeatedly). Cleared once the refresh settles.
 */
let refreshInFlight: Observable<string> | null = null;

/**
 * Functional JWT auth interceptor.
 *
 * - Attaches `Authorization: Bearer <accessToken>` when a token is present — but
 *   ONLY for requests to our own API (relative paths or the configured
 *   {@link API_BASE_URL}). Calls to third-party absolute URLs (e.g. the India
 *   Post pincode API) must never receive our JWT, so they are left untouched.
 * - On a 401 response, attempts a ONE-TIME silent token refresh using the stored
 *   refresh token ({@code POST /api/auth/refresh}) and retries the original
 *   request with the new access token. This keeps a signed-in user working past
 *   the short (15-min) access-token lifetime without being bounced to login
 *   mid-task (e.g. while filling a multi-step order). Only when the refresh
 *   itself fails (or there is no refresh token) does it clear the session and
 *   emit an `unauthorized` event (Req 5.2 — authentication required).
 * - On a 403 response, emits a `forbidden` event so the app can show an
 *   authorization error (Req 5.3).
 *
 * Register with `provideHttpClient(withInterceptors([authInterceptor]))`.
 */
export const authInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
): Observable<HttpEvent<unknown>> => {
  const tokens = inject(AuthTokenStore);
  const authEvents = inject(AuthEventsService);
  const apiBaseUrl = inject(API_BASE_URL, { optional: true }) ?? '';

  const isOurApi = isApiRequest(req.url, apiBaseUrl);
  const isPublicAuth = isPublicAuthRequest(req.url, apiBaseUrl);

  const withToken = (token: string | null): HttpRequest<unknown> =>
    token && isOurApi && !isPublicAuth
      ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : req;

  return next(withToken(tokens.getAccessToken())).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        return throwError(() => error);
      }

      if (error.status === 401) {
        // Only attempt a silent refresh for our own API calls that carried a
        // token — never for the auth endpoints themselves (login/refresh/register),
        // which would loop, and never for third-party hosts.
        const canRefresh =
          isOurApi && !isPublicAuth && !!tokens.getRefreshToken() && !!tokens.getAccessToken();
        if (canRefresh) {
          return refreshAccessToken(tokens, apiBaseUrl, next).pipe(
            switchMap((newToken) => next(withToken(newToken))),
            catchError((refreshError: unknown) => {
              // Refresh failed → the session is genuinely over.
              tokens.clear();
              authEvents.emit({ kind: 'unauthorized', status: 401, url: req.url });
              return throwError(() => refreshError);
            }),
          );
        }
        // No way to refresh (no refresh token, or an auth-endpoint 401).
        tokens.clear();
        authEvents.emit({ kind: 'unauthorized', status: 401, url: req.url });
      } else if (error.status === 403) {
        authEvents.emit({ kind: 'forbidden', status: 403, url: req.url });
      }
      return throwError(() => error);
    }),
  );
};

/**
 * Performs (or joins) the shared one-time refresh: POSTs the stored refresh token
 * to {@code /api/auth/refresh} via the same handler chain, stores the new pair,
 * and yields the new access token. Concurrent 401s share the single in-flight
 * request via {@link refreshInFlight}.
 */
function refreshAccessToken(
  tokens: AuthTokenStore,
  apiBaseUrl: string,
  next: HttpHandlerFn,
): Observable<string> {
  if (refreshInFlight) {
    return refreshInFlight;
  }
  const base = apiBaseUrl.replace(/\/+$/, '');
  const refreshUrl = `${base}/api/auth/refresh`;
  const refreshReq = new HttpRequest('POST', refreshUrl, {
    refreshToken: tokens.getRefreshToken() ?? '',
  });

  refreshInFlight = next(refreshReq).pipe(
    // The handler emits progress/sent events too; keep only the final response.
    filter((event): event is HttpResponse<unknown> => event instanceof HttpResponse),
    take(1),
    map((response) => {
      const pair = response.body as RefreshTokenResponse | null;
      if (!pair || !pair.accessToken) {
        throw new Error('Refresh response missing access token.');
      }
      tokens.setTokens(pair.accessToken, pair.refreshToken);
      return pair.accessToken;
    }),
    // Reset the shared slot once the refresh settles (on the source, so it fires
    // once) — a later expiry can then refresh again.
    finalize(() => {
      refreshInFlight = null;
    }),
    // Share the single result across all waiting requests (no refCount, so an
    // early unsubscribe by one waiter doesn't cancel the refresh for the others).
    shareReplay(1),
  );
  return refreshInFlight;
}
