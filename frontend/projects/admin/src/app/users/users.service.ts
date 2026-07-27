import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Role } from 'core';

/**
 * The staff roles a managed account may hold. Excludes {@link Role.CUSTOMER},
 * which is only ever created through self-registration on the storefront — staff
 * management never creates or assigns the customer role.
 */
export type StaffRole =
  | Role.ADMIN
  | Role.ACCOUNTANT
  | Role.SALESPERSON
  | Role.TEAM_LEAD
  | Role.PACKING_USER
  | Role.PAYMENT_VERIFIER;

/** The staff roles offered in the create/edit dropdowns, in display order. */
export const STAFF_ROLES: readonly StaffRole[] = [
  Role.ADMIN,
  Role.ACCOUNTANT,
  Role.SALESPERSON,
  Role.TEAM_LEAD,
  Role.PACKING_USER,
  Role.PAYMENT_VERIFIER,
];

/** Government ID document types accepted for a staff member (mirrors backend `IdProofType`). */
export type IdProofType =
  | 'AADHAAR'
  | 'PAN'
  | 'DRIVING_LICENSE'
  | 'VOTER_ID'
  | 'PASSPORT'
  | 'OTHER';

/** ID proof options for the edit form, with human labels. */
export const ID_PROOF_TYPES: readonly { value: IdProofType; label: string }[] = [
  { value: 'AADHAAR', label: 'Aadhaar' },
  { value: 'PAN', label: 'PAN' },
  { value: 'DRIVING_LICENSE', label: 'Driving License' },
  { value: 'VOTER_ID', label: 'Voter ID' },
  { value: 'PASSPORT', label: 'Passport' },
  { value: 'OTHER', label: 'Other' },
];

/** Identity-verification state (read-only on the Users page; managed on Salespeople). */
export type VerificationStatus = 'PENDING' | 'VERIFIED' | 'REJECTED';

/**
 * Admin view of a user account (mirrors the backend user list DTO). The role may
 * be any platform {@link Role} on read (a CUSTOMER can appear), but only staff
 * roles are ever written back from this screen. Carries the full editable
 * profile so the edit form can be pre-filled.
 */
export interface AdminUser {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  active: boolean;
  createdAt: string;
  email: string | null;
  mobile: string | null;
  dateOfBirth: string | null;
  address: string | null;
  joinedOn: string | null;
  idProofType: IdProofType | null;
  idProofNumber: string | null;
  verificationStatus: VerificationStatus | null;
  teamLeadId: number | null;
}

/** Payload for creating a staff account ({@code POST /api/admin/users}). */
export interface CreateUserRequest {
  username: string;
  password: string;
  fullName: string;
  role: StaffRole;
  active: boolean;
}

/**
 * Payload for editing a staff account ({@code PUT /api/admin/users/{id}}).
 * The admin can change every detail here; profile fields are optional.
 */
export interface UpdateUserRequest {
  fullName: string;
  role: StaffRole;
  active: boolean;
  email?: string | null;
  mobile?: string | null;
  dateOfBirth?: string | null;
  address?: string | null;
  joinedOn?: string | null;
  idProofType?: IdProofType | null;
  idProofNumber?: string | null;
}

/**
 * Data access for ADMIN-only staff user management ({@code /api/admin/users}).
 *
 * <p>Calls go through the shared {@link ApiClient} so the auth interceptor
 * attaches the bearer token; the backend enforces the ADMIN role and the
 * guardrails (e.g. you cannot deactivate your own account, duplicate username).
 */
@Injectable({ providedIn: 'root' })
export class UsersService {
  private readonly api = inject(ApiClient);

  /** All staff/user accounts for the management table. */
  list(): Observable<AdminUser[]> {
    return this.api.get<AdminUser[]>('/api/admin/users');
  }

  /** Creates a staff account; a duplicate username is rejected with a 409. */
  create(request: CreateUserRequest): Observable<AdminUser> {
    return this.api.post<AdminUser>('/api/admin/users', request);
  }

  /** Updates a staff account's name, role and active flag. */
  update(id: number, request: UpdateUserRequest): Observable<AdminUser> {
    return this.api.put<AdminUser>(`/api/admin/users/${id}`, request);
  }

  /** Sets a new password for the account. */
  resetPassword(id: number, newPassword: string): Observable<void> {
    return this.api.post<void>(`/api/admin/users/${id}/reset-password`, { newPassword });
  }

  /** Re-enables sign-in for the account. */
  activate(id: number): Observable<AdminUser> {
    return this.api.post<AdminUser>(`/api/admin/users/${id}/activate`, null);
  }

  /** Blocks sign-in for the account (subject to backend guardrails). */
  deactivate(id: number): Observable<AdminUser> {
    return this.api.post<AdminUser>(`/api/admin/users/${id}/deactivate`, null);
  }
}
