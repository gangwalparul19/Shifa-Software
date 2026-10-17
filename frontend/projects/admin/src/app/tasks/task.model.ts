/** CRM task read/write models (CRM expansion Phase 2, Task 10). */

export type TaskPriority = 'LOW' | 'NORMAL' | 'HIGH';
export type TaskStatus = 'OPEN' | 'DONE' | 'CANCELLED';

export interface Task {
  id: number;
  ownerUserId: number;
  title: string;
  description?: string | null;
  leadId?: number | null;
  customerMobile?: string | null;
  dueAt?: string | null;
  priority: TaskPriority;
  status: TaskStatus;
  completedAt?: string | null;
  createdAt: string;
}

export interface CreateTaskRequest {
  title: string;
  description?: string | null;
  leadId?: number | null;
  customerMobile?: string | null;
  dueAt?: string | null;
  priority?: TaskPriority | null;
  ownerUserId?: number | null;
}

export interface UpdateTaskRequest {
  title: string;
  description?: string | null;
  leadId?: number | null;
  customerMobile?: string | null;
  dueAt?: string | null;
  priority?: TaskPriority | null;
}

export const TASK_PRIORITIES: TaskPriority[] = ['LOW', 'NORMAL', 'HIGH'];

export function taskPriorityClass(p: TaskPriority): string {
  switch (p) {
    case 'HIGH':
      return 'badge tone-red';
    case 'NORMAL':
      return 'badge tone-blue';
    case 'LOW':
      return 'badge tone-grey';
  }
}

export function taskStatusClass(s: TaskStatus): string {
  switch (s) {
    case 'OPEN':
      return 'badge tone-amber';
    case 'DONE':
      return 'badge tone-green';
    case 'CANCELLED':
      return 'badge tone-grey';
  }
}
