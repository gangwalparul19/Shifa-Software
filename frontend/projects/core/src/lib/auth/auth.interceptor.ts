import { inject } from '@angular/core';
import {
  HttpErrorResponse,
  HttpEvent,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { Observable, throwError } from 'rxjs';
import { catchError } from 'rxjs/operators';
import { AuthTokenStore } from './auth-token.store';
import { AuthEventsService } from './auth-events.service';
import { API_BASE_URL } from '../tokens/api-base-url.token';
import { isApiRequest, isPublicAuthRequest } from './auth-request-scope';

/**
 * Functional JWT auth interceptor.
 *
 * - Attaches `Authorization: Bearer <accessToken>` when a token is present — but
 *   ONLY for requests to our own API (relative paths or the configured
 *   {@link API_BASE_URL}). Calls to third-party absolute URLs (e.g. the India
 *   Post pincode API) must never receive our JWT, so they are left untouched.
 * - On a 401 response, clears the session and emits an `unauthorized` event
 *   (Req 5.2 — authentication required).
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

  const accessToken = tokens.getAccessToken();
  const authorizedReq =
    accessToken && isApiRequest(req.url, apiBaseUrl) && !isPublicAuthRequest(req.url, apiBaseUrl)
      ? req.clone({ setHeaders: { Authorization: `Bearer ${accessToken}` } })
      : req;

  return next(authorizedReq).pipe(
    catchError((error: unknown) => {
      if (error instanceof HttpErrorResponse) {
        if (error.status === 401) {
          tokens.clear();
          authEvents.emit({ kind: 'unauthorized', status: 401, url: req.url });
        } else if (error.status === 403) {
          authEvents.emit({ kind: 'forbidden', status: 403, url: req.url });
        }
      }
      return throwError(() => error);
    }),
  );
};
