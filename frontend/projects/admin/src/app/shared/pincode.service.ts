import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError, map, timeout } from 'rxjs/operators';

/** The resolved locality for a pincode (city = post-office district, plus state). */
export interface PincodeLocation {
  city: string;
  state: string;
}

/** Shape of a single post office in the India Post API response. */
interface IndiaPostOffice {
  District?: string;
  State?: string;
}

/** Shape of one entry in the India Post pincode API response array. */
interface IndiaPostResult {
  Status?: string;
  PostOffice?: IndiaPostOffice[] | null;
}

/**
 * Resolves an Indian 6-digit PIN code to its city (district) + state using the
 * free, key-less India Post public API ({@code api.postalpincode.in}).
 *
 * <p>This is a convenience for salespeople punching orders (product-audit
 * PIN-code auto-fill): typing a pincode pre-fills the City and State fields so
 * they don't have to. It is strictly best-effort — the lookup is an external
 * call that may be blocked (offline PWA), rate-limited, or return no match, in
 * which case {@link lookup} emits {@code null} and the salesperson simply types
 * the city/state by hand as before. No auth token or customer data is sent; only
 * the pincode leaves the browser.
 */
@Injectable({ providedIn: 'root' })
export class PincodeService {
  private readonly http = inject(HttpClient);

  /** Simple in-memory cache so re-typing the same pincode doesn't re-hit the API. */
  private readonly cache = new Map<string, PincodeLocation | null>();

  /**
   * Looks up the city + state for a 6-digit pincode. Returns {@code null} for a
   * malformed pincode, a failed/blocked request, or no match — never throws.
   */
  lookup(pincode: string): Observable<PincodeLocation | null> {
    if (!/^\d{6}$/.test(pincode)) {
      return of(null);
    }
    const cached = this.cache.get(pincode);
    if (cached !== undefined) {
      return of(cached);
    }
    // Skip the network entirely when we already know we're offline.
    if (typeof navigator !== 'undefined' && navigator.onLine === false) {
      return of(null);
    }
    return this.http
      .get<IndiaPostResult[]>(`https://api.postalpincode.in/pincode/${pincode}`)
      .pipe(
        timeout(5000),
        map((results) => this.toLocation(results)),
        map((location) => {
          this.cache.set(pincode, location);
          return location;
        }),
        catchError(() => of(null)),
      );
  }

  /** Maps the India Post response to a {@link PincodeLocation}, or null if absent. */
  private toLocation(results: IndiaPostResult[] | null): PincodeLocation | null {
    const first = results?.[0];
    const office = first?.Status === 'Success' ? first.PostOffice?.[0] : undefined;
    const city = office?.District?.trim();
    const state = office?.State?.trim();
    if (!city || !state) {
      return null;
    }
    return { city, state };
  }
}
