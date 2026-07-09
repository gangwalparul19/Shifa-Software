import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Role } from 'core';

/** Accepted government ID document types (mirrors the backend IdProofType enum). */
export type IdProofType =
  | 'AADHAAR'
  | 'PAN'
  | 'DRIVING_LICENSE'
  | 'VOTER_ID'
  | 'PASSPORT'
  | 'OTHER';

/** The ID proof types offered in the dropdown, in display order. */
export const ID_PROOF_TYPES: readonly IdProofType[] = [
  'AADHAAR',
  'PAN',
  'DRIVING_LICENSE',
  'VOTER_ID',
  'PASSPORT',
  'OTHER',
];

/** Verification state (mirrors the backend VerificationStatus enum). */
export type VerificationStatus = 'PENDING' | 'VERIFIED' | 'REJECTED';

/** Status of a self-service profile change request (mirrors ChangeRequestStatus). */
export type ChangeRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED';

/** The editable profile fields, shown as current vs proposed. */
export interface ProfileFields {
  fullName: string | null;
  email: string | null;
  mobile: string | null;
  dateOfBirth: string | null;
  address: string | null;
  idProofType: IdProofType | null;
  idProofNumber: string | null;
}

/** A staff profile change request (admin queue + employee view). */
export interface ProfileChangeRequest {
  id: number;
  userId: number;
  username: string;
  fullNameOfUser: string;
  status: ChangeRequestStatus;
  current: ProfileFields;
  proposed: ProfileFields;
  requestNote: string | null;
  reviewNote: string | null;
  requestedAt: string;
  reviewedAt: string | null;
}

/** Admin view of a staff member's onboarding profile (mirrors StaffProfileResponse). */
export interface StaffProfile {
  id: number;
  username: string;
  fullName: string;
  role: Role;
  active: boolean;
  email: string | null;
  mobile: string | null;
  dateOfBirth: string | null;
  address: string | null;
  joinedOn: string | null;
  idProofType: IdProofType | null;
  idProofNumber: string | null;
  hasIdProof: boolean;
  hasProfileImage: boolean;
  verificationStatus: VerificationStatus;
  verificationNote: string | null;
  verifiedAt: string | null;
  createdAt: string;
}

/** Payload to capture/update a staff profile ({@code PUT /api/admin/staff/{id}}). */
export interface UpdateStaffProfileRequest {
  fullName: string;
  email: string | null;
  mobile: string | null;
  dateOfBirth: string | null;
  address: string | null;
  joinedOn: string | null;
  idProofType: IdProofType | null;
  idProofNumber: string | null;
}

/**
 * Data access for ADMIN-only staff onboarding &amp; ID verification
 * ({@code /api/admin/staff}). JSON calls go through {@link ApiClient} (the auth
 * interceptor attaches the bearer token); the ID proof document is a binary
 * download fetched with {@link HttpClient} as a {@code Blob}.
 */
@Injectable({ providedIn: 'root' })
export class SalespeopleService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** The "All salespeople" directory. */
  list(): Observable<StaffProfile[]> {
    return this.api.get<StaffProfile[]>('/api/admin/staff');
  }

  /** A single staff member's full profile. */
  get(id: number): Observable<StaffProfile> {
    return this.api.get<StaffProfile>(`/api/admin/staff/${id}`);
  }

  /** Captures/updates the staff member's onboarding profile fields. */
  updateProfile(id: number, request: UpdateStaffProfileRequest): Observable<StaffProfile> {
    return this.api.put<StaffProfile>(`/api/admin/staff/${id}`, request);
  }

  /** Uploads (or replaces) the staff member's ID proof document. */
  uploadIdProof(id: number, file: File): Observable<StaffProfile> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<StaffProfile>(`/api/admin/staff/${id}/id-proof`, form);
  }

  /** Fetches the ID proof document as a Blob for inline viewing/download. */
  idProof(id: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/admin/staff/${id}/id-proof`), {
      responseType: 'blob',
    });
  }

  /** Uploads (or replaces) the staff member's profile photo (image only; compressed server-side). */
  uploadProfileImage(id: number, file: File): Observable<StaffProfile> {
    const form = new FormData();
    form.append('file', file);
    return this.api.post<StaffProfile>(`/api/admin/staff/${id}/profile-image`, form);
  }

  /** Fetches the profile photo as a Blob for inline display. */
  profileImage(id: number): Observable<Blob> {
    return this.http.get(this.api.url(`/api/admin/staff/${id}/profile-image`), {
      responseType: 'blob',
    });
  }

  /** Records a verify/reject decision for the staff member. */
  verify(id: number, status: VerificationStatus, note: string | null): Observable<StaffProfile> {
    return this.api.post<StaffProfile>(`/api/admin/staff/${id}/verify`, { status, note });
  }

  /** Staff profile change requests awaiting admin approval. */
  changeRequests(): Observable<ProfileChangeRequest[]> {
    return this.api.get<ProfileChangeRequest[]>('/api/admin/staff/change-requests');
  }

  /** Approve (apply) or reject a staff profile change request. */
  reviewChangeRequest(
    id: number,
    status: Exclude<ChangeRequestStatus, 'PENDING'>,
    note: string | null,
  ): Observable<ProfileChangeRequest> {
    return this.api.post<ProfileChangeRequest>(
      `/api/admin/staff/change-requests/${id}/review`,
      { status, note },
    );
  }
}
