import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

/** Reason an auth event was emitted by the interceptor. */
export type AuthEventKind = 'unauthorized' | 'forbidden';

export interface AuthEvent {
  kind: AuthEventKind;
  /** HTTP status that triggered the event (401 or 403). */
  status: number;
  /** Request URL that failed. */
  url: string;
}

/**
 * Broadcasts authentication/authorization failures detected by the interceptor
 * (401 -> unauthorized, 403 -> forbidden) so each app can react appropriately
 * (redirect to login, show an authorization error). Route guards and the full
 * refresh/redirect flow subscribe to this in a later task.
 */
@Injectable({ providedIn: 'root' })
export class AuthEventsService {
  private readonly events$ = new Subject<AuthEvent>();

  /** Stream of auth failures for apps to subscribe to. */
  readonly events = this.events$.asObservable();

  emit(event: AuthEvent): void {
    this.events$.next(event);
  }
}
