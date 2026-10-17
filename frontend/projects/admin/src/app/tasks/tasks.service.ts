import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { CreateTaskRequest, Task, TaskStatus, UpdateTaskRequest } from './task.model';

/**
 * Data access for CRM tasks (`/api/tasks`, CRM expansion Phase 2, Task 10).
 * Owner scoping (own / team / all) is enforced server-side.
 */
@Injectable({ providedIn: 'root' })
export class TasksService {
  private readonly api = inject(ApiClient);

  list(status?: TaskStatus | null): Observable<Task[]> {
    const params: Record<string, string> = {};
    if (status) {
      params['status'] = status;
    }
    return this.api.get<Task[]>('/api/tasks', Object.keys(params).length ? { params } : undefined);
  }

  create(payload: CreateTaskRequest): Observable<Task> {
    return this.api.post<Task>('/api/tasks', payload);
  }

  update(id: number, payload: UpdateTaskRequest): Observable<Task> {
    return this.api.put<Task>(`/api/tasks/${id}`, payload);
  }

  complete(id: number): Observable<Task> {
    return this.api.post<Task>(`/api/tasks/${id}/complete`, {});
  }
}
