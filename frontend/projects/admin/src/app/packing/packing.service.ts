import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { PackingScanResponse } from './packing.model';

/**
 * Data access for the packing barcode-scan workflow (Req 11).
 *
 * <p>Posts the scanned barcode to {@code POST /api/packing/scan} through the
 * shared {@link ApiClient} (the auth interceptor attaches the bearer token). A
 * success resolves with the packed order; the not-recognized (404) and
 * wrong-status (409) outcomes surface as {@code HttpErrorResponse}s carrying the
 * standard {@code ApiError} envelope, which the component interprets.
 */
@Injectable({ providedIn: 'root' })
export class PackingService {
  private readonly api = inject(ApiClient);

  /** Scan a barcode to mark the matching order Packed (Req 11.1). */
  scan(barcode: string): Observable<PackingScanResponse> {
    return this.api.post<PackingScanResponse>('/api/packing/scan', { barcode });
  }
}
