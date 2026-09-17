import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { AdminExceptionResponse } from './exceptions.model';

@Injectable({ providedIn: 'root' })
export class ExceptionsService {
  private readonly api = inject(ApiClient);

  list(): Observable<AdminExceptionResponse> {
    return this.api.get<AdminExceptionResponse>('/api/admin/exceptions');
  }
}
