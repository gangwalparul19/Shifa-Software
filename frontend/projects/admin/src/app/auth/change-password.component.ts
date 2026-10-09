import { Component, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from 'core';

import {
  PASSWORD_REQUIREMENTS,
  passwordPolicyValidator,
  passwordsMatchValidator,
} from '../shared/password-policy';
import { BRAND } from '../shared/brand';

/**
 * Forced / self-service "set a new password" screen. Shown after an admin
 * resets a user's password: the user signs in with the temporary password
 * {@code Welcome@123} and is routed here (by the login flow + the shell's
 * force-change guard) to choose their own strong password before using the app.
 *
 * <p>Lives outside the admin shell (like login) so a forced user sees no app
 * chrome. On success the fresh token (without the force-change flag) is stored
 * and the user lands on the dashboard.
 */
@Component({
  selector: 'admin-change-password',
  imports: [ReactiveFormsModule],
  templateUrl: './change-password.component.html',
  styleUrl: './login.component.css',
})
export class ChangePasswordComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);
  protected readonly brand = BRAND.name;
  protected readonly requirements = PASSWORD_REQUIREMENTS;

  /** Whether this is a forced change (admin reset) vs a voluntary one. */
  protected readonly forced = computed(() => this.auth.mustChangePassword());

  protected readonly form = this.fb.nonNullable.group(
    {
      newPassword: ['', [Validators.required, passwordPolicyValidator()]],
      confirmPassword: ['', [Validators.required]],
    },
    { validators: passwordsMatchValidator('newPassword', 'confirmPassword') },
  );

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.changePassword(this.form.getRawValue().newPassword).subscribe({
      next: () => {
        void this.router.navigateByUrl('/dashboard');
      },
      error: (err: HttpErrorResponse) => {
        this.submitting.set(false);
        this.error.set(
          err?.error?.message ??
            'Could not update your password. Make sure it meets the requirements and try again.',
        );
      },
    });
  }

  /** Signs out and returns to login (e.g. the user wants to switch accounts). */
  cancel(): void {
    this.auth.logout();
    void this.router.navigateByUrl('/login');
  }
}
