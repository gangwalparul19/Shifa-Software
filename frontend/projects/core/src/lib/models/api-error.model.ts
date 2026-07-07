/**
 * Standard JSON error envelope returned by the backend on failure.
 * Mirrors `com.shifa.oms.common.ErrorResponse`.
 */
export interface ApiError {
  timestamp: string;
  status: number;
  error: string;
  /** Machine-readable code, e.g. `DUPLICATE_SKU`, `ILLEGAL_STATUS_TRANSITION`. */
  code: string;
  message: string;
  path: string;
  details?: string[];
}
