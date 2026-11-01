import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

export type BankAccountType =
  | 'UPI_QR'
  | 'BANK_TRANSFER'
  | 'SHOPIFY_SETTLEMENT'
  | 'QUICSHIP_REMITTANCE'
  | 'CASH'
  | 'OTHER';

/** Account purposes with their display labels (shared by the account and payment-method managers). */
export const BANK_ACCOUNT_TYPE_OPTIONS: { value: BankAccountType; label: string }[] = [
  { value: 'UPI_QR', label: 'UPI / QR account' },
  { value: 'BANK_TRANSFER', label: 'Bank transfer account' },
  { value: 'SHOPIFY_SETTLEMENT', label: 'Shopify settlement' },
  { value: 'QUICSHIP_REMITTANCE', label: 'QuikShip remittance' },
  { value: 'CASH', label: 'Cash / counter' },
  { value: 'OTHER', label: 'Other' },
];

/** Display label for an account purpose; falls back to the raw code. */
export function bankAccountTypeLabel(type: BankAccountType): string {
  return BANK_ACCOUNT_TYPE_OPTIONS.find((option) => option.value === type)?.label ?? type;
}

/** Payment method as managed by an admin under Settings (ADMIN endpoint). */
export interface PaymentMethodConfig {
  id: number;
  /** Immutable key stored on each payment. */
  code: string;
  label: string;
  /** The salesperson must name the account that received the money. */
  requiresAccount: boolean;
  /** When set, the receiving account is auto-selected from accounts of this purpose. */
  linkedAccountType?: BankAccountType | null;
  active: boolean;
  defaultMethod: boolean;
  sortOrder: number;
  /** Payments recorded with this method; a used method can be deactivated but not deleted. */
  usageCount: number;
}

export interface PaymentMethodRequest {
  label: string;
  requiresAccount: boolean;
  linkedAccountType: BankAccountType | null;
  active: boolean;
  defaultMethod: boolean;
  sortOrder: number;
}

/** Active payment method as offered at order entry. */
export interface PaymentMethodOption {
  code: string;
  label: string;
  requiresAccount: boolean;
  linkedAccountType?: BankAccountType | null;
  /** Account to pre-select when the method is linked (null when none is set up). */
  autoAccountId?: number | null;
  defaultMethod: boolean;
}

export interface BankAccount {
  id: number;
  displayName: string;
  accountType: BankAccountType;
  bankName?: string | null;
  accountHolderName?: string | null;
  maskedAccountNumber?: string | null;
  ifsc?: string | null;
  branch?: string | null;
  upiId?: string | null;
  ledgerAccountId?: number | null;
  active: boolean;
  defaultAccount: boolean;
  sortOrder: number;
  internalNote?: string | null;
}

export interface PaymentAccountOption {
  id: number;
  displayName: string;
  accountType: BankAccountType;
  bankName?: string | null;
  maskedAccountNumber?: string | null;
  upiId?: string | null;
}

export interface BankAccountRequest {
  displayName: string;
  accountType: BankAccountType;
  bankName?: string | null;
  accountHolderName?: string | null;
  accountNumber?: string | null;
  ifsc?: string | null;
  branch?: string | null;
  upiId?: string | null;
  ledgerAccountId?: number | null;
  active: boolean;
  defaultAccount: boolean;
  sortOrder: number;
  internalNote?: string | null;
}

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
  /** Default low-stock alert threshold (used when a product has no override). */
  lowStockThreshold?: number | null;
  /** Seller aggregate turnover (₹) — drives GSTR-1 HSN digit length (4 vs 6). */
  aggregateTurnover?: string | null;
  /** GST filing-calendar reminder look-ahead, in whole days (1–30; default 7). */
  gstReminderWindowDays?: number | null;
  /** GST reconciliation match tolerance in ₹ (0.00–9999.99; default 1.00). */
  gstReconciliationTolerance?: string | null;
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
  /** Never returned in full; the writable request uses blank to preserve it. */
  bankAccountNumber?: string | null;
  /** Last-four mask returned by the settings read projection. */
  maskedBankAccountNumber?: string | null;
  /** Bank IFSC code (validated against the IFSC pattern when present). */
  bankIfsc?: string | null;
  /** Bank branch. */
  bankBranch?: string | null;
  // --- Order auto-approval (V73, DEFAULT OFF) -----------------------------
  /**
   * When true, low-value fully-prepaid orders from low-risk customers are
   * auto-approved at creation (skip the manual approval queue).
   */
  autoApproveEnabled?: boolean;
  /** Max order total (₹) eligible for auto-approval; null/0 means nothing qualifies. */
  autoApproveMaxAmount?: string | null;
  /** Whether Shopify payout settlement tracking is enabled. */
  shopifySettlementEnabled?: boolean;
  /** Active bank-account id expected to receive Shopify payouts. */
  shopifySettlementAccountId?: number | null;
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

  /** ADMIN bank-account master list for the Settings page. */
  bankAccounts(): Observable<BankAccount[]> {
    return this.api.get<BankAccount[]>('/api/admin/settings/bank-accounts');
  }

  createBankAccount(request: BankAccountRequest): Observable<BankAccount> {
    return this.api.post<BankAccount>('/api/admin/settings/bank-accounts', request);
  }

  updateBankAccount(id: number, request: BankAccountRequest): Observable<BankAccount> {
    return this.api.put<BankAccount>(`/api/admin/settings/bank-accounts/${id}`, request);
  }

  deactivateBankAccount(id: number): Observable<BankAccount> {
    return this.api.delete<BankAccount>(`/api/admin/settings/bank-accounts/${id}`);
  }

  /** Safe active accounts for the salesperson payment selector. */
  activePaymentAccounts(): Observable<PaymentAccountOption[]> {
    return this.api.get<PaymentAccountOption[]>('/api/payment-accounts');
  }

  /** Active payment methods for the order-entry selector (default flagged). */
  activePaymentMethods(): Observable<PaymentMethodOption[]> {
    return this.api.get<PaymentMethodOption[]>('/api/payment-methods');
  }

  /** ADMIN payment-method master list for the Settings page. */
  paymentMethods(): Observable<PaymentMethodConfig[]> {
    return this.api.get<PaymentMethodConfig[]>('/api/admin/settings/payment-methods');
  }

  createPaymentMethod(request: PaymentMethodRequest): Observable<PaymentMethodConfig> {
    return this.api.post<PaymentMethodConfig>('/api/admin/settings/payment-methods', request);
  }

  updatePaymentMethod(id: number, request: PaymentMethodRequest): Observable<PaymentMethodConfig> {
    return this.api.put<PaymentMethodConfig>(`/api/admin/settings/payment-methods/${id}`, request);
  }

  makeDefaultPaymentMethod(id: number): Observable<PaymentMethodConfig> {
    return this.api.post<PaymentMethodConfig>(`/api/admin/settings/payment-methods/${id}/default`, {});
  }

  /** Deletes a never-used method; the server refuses one that already has payments. */
  deletePaymentMethod(id: number): Observable<void> {
    return this.api.delete<void>(`/api/admin/settings/payment-methods/${id}`);
  }
}
