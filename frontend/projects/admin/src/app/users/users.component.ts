import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ApiError, AuthService, Role } from 'core';
import {
  AdminUser,
  CreateUserRequest,
  STAFF_ROLES,
  StaffRole,
  UpdateUserRequest,
  UsersService,
} from './users.service';
import { PageHeaderComponent } from '../shared/page-header.component';
import { PaginationComponent } from '../shared/pagination.component';
import { readPageSize, writePageSize } from '../shared/page-size.util';
import { StatePanelComponent } from '../shared/state-panel.component';
import { DensityToggleComponent } from '../shared/density-toggle.component';
import { RowActionsMenuComponent, RowAction } from '../shared/row-actions-menu.component';
import { ConfirmService } from '../shared/confirm.service';
import { ToastService } from '../shared/toast.service';

/**
 * Admin-only staff user management (Req 5.4).
 *
 * <p>A Tabler-styled, responsive table of staff accounts (username, full name,
 * role badge, active/inactive badge, created) with create/edit modals, a
 * reset-password modal, and activate/deactivate actions. Only staff roles are
 * offered when creating/editing (never CUSTOMER). Server guardrails (duplicate
 * username 409, "cannot deactivate your own account" 400) are surfaced inline on
 * the form or as a toast. Route + nav are ADMIN-guarded.
 */
@Component({
  selector: 'admin-users',
  imports: [
    ReactiveFormsModule,
    PageHeaderComponent,
    PaginationComponent,
    StatePanelComponent,
    DensityToggleComponent,
    RowActionsMenuComponent,
  ],
  templateUrl: './users.component.html',
  styleUrl: './users.component.css',
})
export class UsersComponent implements OnInit {
  private readonly service = inject(UsersService);
  private readonly fb = inject(FormBuilder);
  private readonly confirmService = inject(ConfirmService);
  private readonly toasts = inject(ToastService);
  private readonly auth = inject(AuthService);

  protected readonly staffRoles = STAFF_ROLES;

  protected readonly users = signal<AdminUser[]>([]);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly actioningId = signal<number | null>(null);

  // --- Client-side paging -------------------------------------------------
  protected readonly page = signal(0);
  protected readonly size = signal(readPageSize('users', 10));
  protected readonly totalElements = computed(() => this.users().length);
  protected readonly totalPages = computed(() =>
    Math.max(1, Math.ceil(this.totalElements() / this.size())),
  );
  protected readonly pageItems = computed<AdminUser[]>(() => {
    const s = this.page() * this.size();
    return this.users().slice(s, s + this.size());
  });

  /** The user being edited (form open); null when creating or closed. */
  protected readonly editing = signal<AdminUser | null>(null);
  protected readonly creating = signal(false);
  protected readonly formOpen = computed(() => this.creating() || this.editing() !== null);
  protected readonly formError = signal<string | null>(null);

  /** The user whose password is being reset; null when the reset modal is closed. */
  protected readonly resetting = signal<AdminUser | null>(null);
  protected readonly resetSaving = signal(false);
  protected readonly resetError = signal<string | null>(null);

  /** The signed-in admin's username, used to prevent self-deactivation in the UI. */
  protected readonly currentUsername = computed(() => this.auth.session()?.username ?? null);

  protected readonly form = this.fb.nonNullable.group({
    username: ['', [Validators.required, Validators.maxLength(60)]],
    password: ['', [Validators.required, Validators.minLength(6), Validators.maxLength(100)]],
    fullName: ['', [Validators.required, Validators.maxLength(120)]],
    role: [Role.SALESPERSON as StaffRole, [Validators.required]],
    active: [true],
  });

  protected readonly resetForm = this.fb.nonNullable.group({
    newPassword: ['', [Validators.required, Validators.minLength(6), Validators.maxLength(100)]],
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (items) => {
        this.users.set(items);
        this.page.set(0);
        this.loading.set(false);
      },
      error: () => {
        this.loadError.set('Could not load users. Please try again.');
        this.loading.set(false);
      },
    });
  }

  // --- Paging handlers ----------------------------------------------------
  goToPage(p: number): void {
    this.page.set(p);
  }

  setSize(s: number): void {
    this.size.set(s);
    writePageSize('users', s);
    this.page.set(0);
  }

  roleLabel(role: Role | string): string {
    switch (role) {
      case Role.ADMIN:
        return 'Admin';
      case Role.ACCOUNTANT:
        return 'Accountant';
      case Role.SALESPERSON:
        return 'Salesperson';
      case Role.PACKING_USER:
        return 'Packing';
      case Role.CUSTOMER:
        return 'Customer';
      default:
        return String(role);
    }
  }

  /** A Tabler badge tone for each role so they read at a glance. */
  roleBadgeClass(role: Role | string): string {
    switch (role) {
      case Role.ADMIN:
        return 'bg-green-lt';
      case Role.ACCOUNTANT:
        return 'bg-azure-lt';
      case Role.SALESPERSON:
        return 'bg-purple-lt';
      case Role.PACKING_USER:
        return 'bg-orange-lt';
      default:
        return 'bg-secondary-lt';
    }
  }

  /** True when the row represents the currently signed-in admin. */
  isSelf(user: AdminUser): boolean {
    return this.currentUsername() === user.username;
  }

  /** Up-to-two-letter initials for the mobile card avatar. */
  userInitials(name: string): string {
    const parts = (name || '').trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      return '?';
    }
    if (parts.length === 1) {
      return parts[0].slice(0, 2).toUpperCase();
    }
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  }

  createdLabel(iso: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) {
      return iso;
    }
    return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
  }

  /** Per-row kebab actions mirroring the original Edit / Reset / (de)activate buttons. */
  rowActions(user: AdminUser): RowAction[] {
    const actions: RowAction[] = [
      { key: 'edit', label: 'Edit', icon: 'ti-edit' },
      { key: 'reset', label: 'Reset password', icon: 'ti-key' },
    ];
    const blockDeactivate = user.active && this.isSelf(user);
    actions.push({
      key: 'toggle',
      label: user.active ? 'Deactivate' : 'Activate',
      icon: user.active ? 'ti-user-off' : 'ti-user-check',
      variant: user.active ? 'danger' : 'success',
      disabled: this.actioningId() !== null || blockDeactivate,
    });
    return actions;
  }

  /** Dispatches a kebab action for the given user row. */
  onRowAction(key: string, user: AdminUser): void {
    if (key === 'edit') {
      this.openEdit(user);
    } else if (key === 'reset') {
      this.openReset(user);
    } else if (key === 'toggle') {
      this.toggleActive(user);
    }
  }

  // --- Create / edit form -------------------------------------------------

  openCreate(): void {
    this.formError.set(null);
    this.editing.set(null);
    this.form.reset({
      username: '',
      password: '',
      fullName: '',
      role: Role.SALESPERSON as StaffRole,
      active: true,
    });
    this.form.controls.username.enable();
    this.form.controls.password.enable();
    this.creating.set(true);
  }

  openEdit(user: AdminUser): void {
    this.formError.set(null);
    this.creating.set(false);
    // On edit, username is immutable and password is not changed here (use reset).
    const role = this.staffRoles.includes(user.role as StaffRole)
      ? (user.role as StaffRole)
      : (Role.SALESPERSON as StaffRole);
    this.form.reset({
      username: user.username,
      password: '',
      fullName: user.fullName,
      role,
      active: user.active,
    });
    this.form.controls.username.disable();
    this.form.controls.password.disable();
    this.editing.set(user);
  }

  closeForm(): void {
    this.creating.set(false);
    this.editing.set(null);
    this.formError.set(null);
  }

  save(): void {
    if (this.saving()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const raw = this.form.getRawValue();
    this.saving.set(true);
    this.formError.set(null);

    const editing = this.editing();
    if (editing) {
      const request: UpdateUserRequest = {
        fullName: raw.fullName.trim(),
        role: raw.role,
        active: raw.active,
      };
      this.service.update(editing.id, request).subscribe({
        next: (updated) => {
          this.saving.set(false);
          this.users.update((items) => items.map((u) => (u.id === updated.id ? updated : u)));
          this.toasts.success('User updated.');
          this.closeForm();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.formError.set(this.describeError(err));
        },
      });
    } else {
      const request: CreateUserRequest = {
        username: raw.username.trim(),
        password: raw.password,
        fullName: raw.fullName.trim(),
        role: raw.role,
        active: raw.active,
      };
      this.service.create(request).subscribe({
        next: () => {
          this.saving.set(false);
          this.toasts.success('User created.');
          this.closeForm();
          this.load();
        },
        error: (err: HttpErrorResponse) => {
          this.saving.set(false);
          this.formError.set(this.describeError(err));
        },
      });
    }
  }

  // --- Reset password -----------------------------------------------------

  async openReset(user: AdminUser): Promise<void> {
    const confirmed = await this.confirmService.confirm({
      title: 'Reset password',
      message: `Set a new password for "${user.username}"? Their current password will stop working immediately.`,
      confirmLabel: 'Continue',
      icon: 'ti-key',
    });
    if (!confirmed) {
      return;
    }
    this.resetError.set(null);
    this.resetForm.reset({ newPassword: '' });
    this.resetting.set(user);
  }

  closeReset(): void {
    this.resetting.set(null);
    this.resetError.set(null);
  }

  submitReset(): void {
    const user = this.resetting();
    if (!user || this.resetSaving()) {
      return;
    }
    if (this.resetForm.invalid) {
      this.resetForm.markAllAsTouched();
      return;
    }
    this.resetSaving.set(true);
    this.resetError.set(null);
    this.service.resetPassword(user.id, this.resetForm.getRawValue().newPassword).subscribe({
      next: () => {
        this.resetSaving.set(false);
        this.toasts.success(`Password reset for ${user.username}.`);
        this.closeReset();
      },
      error: (err: HttpErrorResponse) => {
        this.resetSaving.set(false);
        this.resetError.set(this.describeError(err));
      },
    });
  }

  // --- Activate / deactivate ---------------------------------------------

  async toggleActive(user: AdminUser): Promise<void> {
    if (this.actioningId() !== null) {
      return;
    }
    if (user.active) {
      const confirmed = await this.confirmService.confirm({
        title: 'Deactivate user',
        message: `Deactivate ${user.username}? They won't be able to sign in.`,
        confirmLabel: 'Deactivate',
        danger: true,
        icon: 'ti-user-off',
      });
      if (!confirmed) {
        return;
      }
    }
    this.actioningId.set(user.id);
    const op$ = user.active ? this.service.deactivate(user.id) : this.service.activate(user.id);
    op$.subscribe({
      next: () => {
        this.actioningId.set(null);
        this.toasts.success(
          user.active ? `${user.username} deactivated.` : `${user.username} activated.`,
        );
        this.load();
      },
      error: (err: HttpErrorResponse) => {
        this.actioningId.set(null);
        this.toasts.error(this.describeError(err));
      },
    });
  }

  // --- Helpers ------------------------------------------------------------

  private describeError(err: HttpErrorResponse): string {
    const body = err.error as ApiError | undefined;
    if (err.status === 409) {
      return body?.message || 'That username is already taken.';
    }
    if (body?.details?.length) {
      return body.details.join(' ');
    }
    return body?.message || 'Something went wrong. Please try again.';
  }
}
