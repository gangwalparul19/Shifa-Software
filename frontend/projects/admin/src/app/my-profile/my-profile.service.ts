import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  IdProofType,
  ProfileChangeRequest,
  StaffProfile,
} from '../salespeople/salespeople.service';

export type { ProfileChangeRequest } from '../salespeople/salespeople.service';

/** The signed-in staff member's profile + any pending change request. */
export interface MyProfile {
  profile: StaffProfile;
  pending: ProfileChangeRequest | null;
}

/** Payload for a self-service change request submission. */
export interface SubmitProfileChangeRequest {
  fullName: string;
  email: string | null;
  mobile: string | null;
  dateOfBirth: string | null;
  address: string | null;
  idProofType: IdProofType | null;
  idProofNumber: string | null;
  requestNote: string | null;
}

/**
 * Data access for the signed-in staff member's own profile ({@code /api/me/profile}).
 *
 * <p>Any staff role may call these; submitting a change request only queues the
 * edit for admin approval — the account is not changed until an admin approves.
 */
@Injectable({ providedIn: 'root' })
export class MyProfileService {
  private readonly api = inject(ApiClient);
  private readonly http = inject(HttpClient);

  /** The current profile + any pending change request. */
  getMine(): Observable<MyProfile> {
    return this.api.get<MyProfile>('/api/me/profile');
  }

  /** Submits (or replaces) a pending change request for admin approval. */
  submit(request: SubmitProfileChangeRequest): Observable<ProfileChangeRequest> {
    return this.api.post<ProfileChangeRequest>('/api/me/profile/change-request', request);
  }

  /** Fetches the signed-in member's own profile photo as a Blob. */
  photo(): Observable<Blob> {
    return this.http.get(this.api.url('/api/me/profile/photo'), { responseType: 'blob' });
  }
}
