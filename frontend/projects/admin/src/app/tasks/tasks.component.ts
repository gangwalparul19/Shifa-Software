import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { ToastService } from '../shared/toast.service';
import { TasksService } from './tasks.service';
import {
  Task,
  TaskPriority,
  TASK_PRIORITIES,
  TaskStatus,
  taskPriorityClass,
  taskStatusClass,
} from './task.model';

/**
 * CRM tasks (CRM expansion Phase 2, Task 10; Req 8): the caller's open/due/
 * overdue to-dos with create / edit / complete. Owner scoping (own / team / all)
 * is enforced server-side. Mobile-first cards; a slide-in form for create/edit.
 */
@Component({
  selector: 'admin-tasks',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    IstDatePipe,
  ],
  templateUrl: './tasks.component.html',
  styleUrl: './tasks.component.css',
})
export class TasksComponent implements OnInit {
  private readonly service = inject(TasksService);
  private readonly toasts = inject(ToastService);
  private readonly fb = inject(FormBuilder);

  protected readonly priorities = TASK_PRIORITIES;
  protected readonly priorityClass = taskPriorityClass;
  protected readonly statusClass = taskStatusClass;

  protected readonly tasks = signal<Task[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly statusFilter = signal<TaskStatus | null>('OPEN');
  protected readonly saving = signal(false);

  // Form drawer state.
  protected readonly drawerOpen = signal(false);
  protected readonly editingId = signal<number | null>(null);

  // Inline close-out prompt (complete / cancel), mirroring the meetings outcome prompt.
  protected readonly promptKind = signal<'complete' | 'cancel' | null>(null);
  protected readonly promptFor = signal<Task | null>(null);
  protected readonly promptText = signal('');

  protected readonly form = this.fb.group({
    title: ['', [Validators.required, Validators.maxLength(200)]],
    description: ['', [Validators.maxLength(1000)]],
    dueAt: [''],
    priority: ['NORMAL' as TaskPriority],
    leadId: [null as number | null],
    customerMobile: [''],
  });

  private readonly today = new Date().toISOString().slice(0, 16);

  protected readonly openCount = computed(
    () => this.tasks().filter((t) => t.status === 'OPEN').length,
  );
  protected readonly overdueCount = computed(
    () => this.tasks().filter((t) => this.isOverdue(t)).length,
  );

  // Client-side paging.
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('tasks', 10));
  protected readonly totalElements = computed(() => this.tasks().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed(() => {
    const s = this.page() * this.size();
    return this.tasks().slice(s, s + this.size());
  });

  ngOnInit(): void {
    this.load();
  }

  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('tasks', s);
    this.page.set(0);
  }

  setFilter(status: TaskStatus | null): void {
    this.statusFilter.set(status);
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list(this.statusFilter()).subscribe({
      next: (rows) => {
        this.tasks.set(rows);
        this.page.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load your tasks. Please try again.');
        this.loading.set(false);
      },
    });
  }

  isOverdue(task: Task): boolean {
    return task.status === 'OPEN' && !!task.dueAt && task.dueAt < this.today;
  }

  openCreate(): void {
    this.editingId.set(null);
    this.form.reset({ title: '', description: '', dueAt: '', priority: 'NORMAL', leadId: null, customerMobile: '' });
    this.drawerOpen.set(true);
  }

  openEdit(task: Task): void {
    this.editingId.set(task.id);
    this.form.reset({
      title: task.title,
      description: task.description ?? '',
      dueAt: task.dueAt ? task.dueAt.slice(0, 16) : '',
      priority: task.priority,
      leadId: task.leadId ?? null,
      customerMobile: task.customerMobile ?? '',
    });
    this.drawerOpen.set(true);
  }

  closeDrawer(): void {
    this.drawerOpen.set(false);
  }

  save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    const payload = {
      title: (v.title ?? '').trim(),
      description: v.description?.trim() || null,
      dueAt: v.dueAt ? v.dueAt : null,
      priority: (v.priority as TaskPriority) ?? 'NORMAL',
      leadId: v.leadId ?? null,
      customerMobile: v.customerMobile?.trim() || null,
    };
    this.saving.set(true);
    const id = this.editingId();
    const req = id == null ? this.service.create(payload) : this.service.update(id, payload);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.drawerOpen.set(false);
        this.toasts.success(id == null ? 'Task created.' : 'Task updated.');
        this.load();
      },
      error: () => {
        this.saving.set(false);
        this.toasts.error('Could not save the task. Please try again.');
      },
    });
  }

  startComplete(task: Task, event: Event): void {
    event.stopPropagation();
    this.promptKind.set('complete');
    this.promptFor.set(task);
    this.promptText.set('');
  }

  startCancel(task: Task, event: Event): void {
    event.stopPropagation();
    this.promptKind.set('cancel');
    this.promptFor.set(task);
    this.promptText.set('');
  }

  closePrompt(): void {
    this.promptKind.set(null);
    this.promptFor.set(null);
    this.promptText.set('');
  }

  onPromptInput(event: Event): void {
    this.promptText.set((event.target as HTMLTextAreaElement).value);
  }

  savePrompt(): void {
    const task = this.promptFor();
    const kind = this.promptKind();
    if (!task || !kind) {
      return;
    }
    const text = this.promptText().trim() || null;
    this.saving.set(true);
    const req =
      kind === 'complete'
        ? this.service.complete(task.id, text)
        : this.service.cancel(task.id, text);
    req.subscribe({
      next: () => {
        this.saving.set(false);
        this.closePrompt();
        this.toasts.success(kind === 'complete' ? 'Task completed.' : 'Task cancelled.');
        this.load();
      },
      error: () => {
        this.saving.set(false);
        this.toasts.error(
          kind === 'complete' ? 'Could not complete the task.' : 'Could not cancel the task.',
        );
      },
    });
  }
}
