import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { catchError, map, timeout } from 'rxjs/operators';

/** A resolved city + state for a 6-digit Indian postal code. */
export interface PincodeLocation {
  city: string;
  state: string;
}

/** Shape of a single Post Office record from the India Post API. */
interface PostOffice {
  Name?: string;
  District?: string;
  State?: string;
}

/** Shape of one India Post API response entry. */
interface PostalResponse {
  Status?: string;
  PostOffice?: PostOffice[] | null;
}

/**
 * Looks up city + state for an Indian postal code via the free public India Post
 * API ({@code https://api.postalpincode.in/pincode/{code}}, no key required).
 *
 * <p>Uses a dedicated {@link HttpClient} built from {@link HttpBackend} so it
 * <em>bypasses the app interceptors</em> — the customer's JWT is never sent to
 * this third-party endpoint. All failures (network, timeout, unknown pin) resolve
 * to {@code null} so callers can silently fall back to manual entry.
 */
@Injectable({ providedIn: 'root' })
export class PincodeService {
  /** Interceptor-free client so no Authorization header leaks to the 3rd party. */
  private readonly http = new HttpClient(inject(HttpBackend));

  /**
   * Resolves the first Post Office's District (city) + State for a valid 6-digit
   * pincode, or {@code null} on any error / unknown code.
   */
  lookup(pincode: string): Observable<PincodeLocation | null> {
    if (!/^\d{6}$/.test(pincode)) {
      return of(null);
    }
    return this.http
      .get<PostalResponse[]>(`https://api.postalpincode.in/pincode/${pincode}`)
      .pipe(
        timeout(6000),
        map((entries) => {
          const entry = entries?.[0];
          const office = entry?.PostOffice?.[0];
          if (entry?.Status !== 'Success' || !office) {
            return null;
          }
          const city = (office.District ?? '').trim();
          const state = (office.State ?? '').trim();
          return city && state ? { city, state } : null;
        }),
        catchError(() => of(null)),
      );
  }
}
