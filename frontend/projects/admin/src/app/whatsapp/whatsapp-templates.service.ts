import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';

/** A customizable WhatsApp message template (mirrors the backend DTO). */
export interface WhatsappTemplate {
  id: number;
  key: string;
  title: string;
  body: string;
  icon: string;
  active: boolean;
  sortOrder: number;
  createdByName?: string | null;
  createdAt?: string;
  updatedAt?: string | null;
}

/** Create / update payload for a WhatsApp template. */
export interface WhatsappTemplateRequest {
  title: string;
  body: string;
  icon?: string;
  active?: boolean;
  sortOrder?: number;
}

/**
 * Client for the customizable WhatsApp templates API (V44). Senders read the
 * active templates; managers (ADMIN / ACCOUNTANT / TEAM_LEAD) manage them.
 */
@Injectable({ providedIn: 'root' })
export class WhatsappTemplatesService {
  private readonly api = inject(ApiClient);

  /** The active templates offered as one-tap quick messages. */
  active(): Observable<WhatsappTemplate[]> {
    return this.api.get<WhatsappTemplate[]>('/api/whatsapp-templates');
  }

  /** Every template (management view), active and inactive. */
  all(): Observable<WhatsappTemplate[]> {
    return this.api.get<WhatsappTemplate[]>('/api/whatsapp-templates/all');
  }

  create(request: WhatsappTemplateRequest): Observable<WhatsappTemplate> {
    return this.api.post<WhatsappTemplate>('/api/whatsapp-templates', request);
  }

  update(id: number, request: WhatsappTemplateRequest): Observable<WhatsappTemplate> {
    return this.api.put<WhatsappTemplate>(`/api/whatsapp-templates/${id}`, request);
  }

  delete(id: number): Observable<void> {
    return this.api.delete<void>(`/api/whatsapp-templates/${id}`);
  }
}
