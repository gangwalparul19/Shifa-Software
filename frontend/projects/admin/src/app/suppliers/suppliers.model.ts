/**
 * A supplier returned by the admin suppliers endpoints
 * ({@code /api/admin/suppliers}, ADMIN only). Mirrors the backend
 * {@code SupplierResponse}.
 */
export interface SupplierResponse {
  id: number;
  name: string;
  contactPerson?: string | null;
  phone?: string | null;
  email?: string | null;
  address?: string | null;
  active: boolean;
  createdAt: string;
}

/**
 * Payload for creating/updating a supplier
 * ({@code POST/PUT /api/admin/suppliers}). Mirrors the backend
 * {@code SupplierRequest} — {@code name} is required (≤150); the remaining
 * fields are optional with their own length/format limits.
 */
export interface SupplierRequest {
  name: string;
  contactPerson?: string | null;
  phone?: string | null;
  email?: string | null;
  address?: string | null;
}
