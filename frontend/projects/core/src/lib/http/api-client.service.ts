import { HttpClient, HttpContext, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE_URL } from '../tokens/api-base-url.token';

/** Optional per-request options forwarded to Angular's `HttpClient`. */
export interface ApiRequestOptions {
  params?:
    | HttpParams
    | Record<string, string | number | boolean | ReadonlyArray<string | number | boolean>>;
  headers?: HttpHeaders | Record<string, string | string[]>;
  context?: HttpContext;
  /** Include credentials/cookies on the request. */
  withCredentials?: boolean;
}

/**
 * Thin typed wrapper around Angular's `HttpClient`.
 *
 * Prefixes every relative path with the configured {@link API_BASE_URL} so
 * feature services call `api.get<T>('/api/catalog/products')` without repeating
 * the host. Auth headers and 401/403 handling are added by the
 * `authInterceptor`, keeping this wrapper focused on URL composition and typing.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(API_BASE_URL);

  get<T>(path: string, options?: ApiRequestOptions): Observable<T> {
    return this.http.get<T>(this.url(path), options);
  }

  post<T>(path: string, body?: unknown, options?: ApiRequestOptions): Observable<T> {
    return this.http.post<T>(this.url(path), body ?? null, options);
  }

  put<T>(path: string, body?: unknown, options?: ApiRequestOptions): Observable<T> {
    return this.http.put<T>(this.url(path), body ?? null, options);
  }

  patch<T>(path: string, body?: unknown, options?: ApiRequestOptions): Observable<T> {
    return this.http.patch<T>(this.url(path), body ?? null, options);
  }

  delete<T>(path: string, options?: ApiRequestOptions): Observable<T> {
    return this.http.delete<T>(this.url(path), options);
  }

  /** Joins the configured base URL with a relative path (absolute URLs pass through). */
  url(path: string): string {
    if (/^https?:\/\//i.test(path)) {
      return path;
    }
    const base = this.baseUrl.replace(/\/+$/, '');
    const suffix = path.startsWith('/') ? path : `/${path}`;
    return `${base}${suffix}`;
  }
}
