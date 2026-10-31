import { HttpClient, HttpContext, HttpHeaders, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { ApiClient } from 'core';
import { filenameFromContentDisposition, openBlob, saveBlob } from './blob-download.util';

/** A downloaded file plus the filename the server chose for it (null when it sent none). */
export interface BlobDownload {
  blob: Blob;
  filename: string | null;
}

export interface BlobRequestOptions {
  params?: HttpParams | Record<string, string | number | boolean | ReadonlyArray<string | number | boolean>>;
  headers?: HttpHeaders | Record<string, string | string[]>;
  context?: HttpContext;
  withCredentials?: boolean;
}

/**
 * Shared authenticated binary transport and browser handoff helper. Requests
 * still use Angular HttpClient, so the existing auth interceptor and silent
 * refresh apply exactly as they do for JSON ApiClient requests.
 */
@Injectable({ providedIn: 'root' })
export class AuthenticatedBlobService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  get(path: string, options?: BlobRequestOptions): Observable<Blob> {
    return this.http.get(this.api.url(path), { ...options, responseType: 'blob' });
  }

  /**
   * As {@link get} but also returns the server's suggested filename from the
   * `Content-Disposition` header, so a download keeps the descriptive name the
   * server gave it (e.g. `shifa-orders_2026-10-02_to_2026-10-31.xlsx`).
   */
  download(path: string, options?: BlobRequestOptions): Observable<BlobDownload> {
    return this.http
      .get(this.api.url(path), { ...options, observe: 'response', responseType: 'blob' })
      .pipe(
        map((response) => ({
          blob: response.body ?? new Blob(),
          filename: filenameFromContentDisposition(response.headers.get('Content-Disposition')),
        })),
      );
  }

  post(path: string, body: unknown, options?: BlobRequestOptions): Observable<Blob> {
    return this.http.post(this.api.url(path), body, { ...options, responseType: 'blob' });
  }

  save(blob: Blob, filename: string | null | undefined, fallbackFilename: string): void {
    saveBlob(blob, filename, fallbackFilename);
  }

  open(blob: Blob, filename: string | null | undefined, fallbackFilename: string): void {
    openBlob(blob, filename, fallbackFilename);
  }
}
