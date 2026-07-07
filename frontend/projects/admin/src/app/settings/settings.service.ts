import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/**
 * Company + GST settings, mirroring the backend {@code SettingsResponse} /
 * {@code SettingsRequest} ({@code GET}/{@code PUT} {@code /api/admin/settings}).
 */
export interface AppSettings {
  gstEnabled: boolean;
  gstin?: string | null;
  legalName: string;
  addressLine?: string | null;
  city?: string | null;
  state?: string | null;
  stateCode?: string | null;
  gstRatePercent: string;
  pricesIncludeGst: boolean;
  invoiceFooterNote?: string | null;
  contactPhone?: string | null;
  contactEmail?: string | null;
  /** Storage key of the uploaded company logo, or null/absent when none is set. */
  logoObjectKey?: string | null;
  // --- Wave 3: invoice & tax / bank details (all optional) ----------------
  /** Prefix applied to generated invoice numbers (e.g. "SHR/24-25/"). */
  invoiceNumberPrefix?: string | null;
  /** Multi-line terms & conditions printed on invoices. */
  invoiceTerms?: string | null;
  /** Comma-separated GST slabs (e.g. "0,5,12,18,28"). */
  gstSlabs?: string | null;
  /** Bank name printed on invoices. */
  bankName?: string | null;
  /** Bank account holder name. */
  bankAccountName?: string | null;
  /** Bank account number. */
  bankAccountNumber?: string | null;
  /** Bank IFSC code (validated against the IFSC pattern when present). */
  bankIfsc?: string | null;
  /** Bank branch. */
  bankBranch?: string | null;
}

/**
 * Data access for admin company + GST settings.
 *
 * <p>Calls go through the shared {@link ApiClient} so the auth interceptor
 * attaches the bearer token. Enabling GST here switches per-order invoices to a
 * GST tax invoice on the backend.
 */
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** Loads the current company + GST settings. */
  get(): Observable<AppSettings> {
    return this.api.get<AppSettings>('/api/admin/settings');
  }

  /** Persists the company + GST settings; GSTIN is required when GST is enabled. */
  update(request: AppSettings): Observable<AppSettings> {
    return this.api.put<AppSettings>('/api/admin/settings', request);
  }

  /**
   * Uploads a company logo (multipart, field name {@code file}). The backend
   * validates the type (png/jpeg) and size (≤1MB) and returns the updated
   * settings including the new {@code logoObjectKey}.
   */
  uploadLogo(file: File): Observable<AppSettings> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<AppSettings>('/api/admin/settings/logo', form);
  }

  /** Removes the current company logo, returning the updated settings. */
  deleteLogo(): Observable<AppSettings> {
    return this.api.delete<AppSettings>('/api/admin/settings/logo');
  }

  /**
   * Fetches the raw logo image as a Blob. Uses {@link HttpClient} directly (via
   * the shared base URL) so the auth interceptor attaches the bearer token —
   * an {@code <img>} src cannot carry the token for this ADMIN-only endpoint.
   */
  getLogo(): Observable<Blob> {
    return this.http.get(this.api.url('/api/admin/settings/logo'), { responseType: 'blob' });
  }
}
