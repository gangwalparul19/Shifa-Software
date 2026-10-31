import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { AssistantAnswer } from './assistant.model';

/**
 * Data access for the in-house Shifa assistant (ADMIN only). The question is
 * answered by the OMS backend from its own database; no external AI service is
 * involved.
 */
@Injectable({ providedIn: 'root' })
export class AssistantService {
  private readonly api = inject(ApiClient);

  /** Asks one question and returns the answer text, optional table and follow-up suggestions. */
  ask(question: string): Observable<AssistantAnswer> {
    return this.api.post<AssistantAnswer>('/api/admin/chatbot/ask', { question });
  }
}
