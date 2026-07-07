import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiError, AuthService } from 'core';

/**
 * Storefront customer registration. Collects full name, a 10-digit mobile, an
 * optional email, and a password; on submit it calls {@code POST /api/auth/register}
 * via the shared {@link AuthService}, which returns a token pair so the customer
 * is auto-logged-in. Navigates to the {@code returnUrl} or the account page.
 * A duplicate account (409) is surfaced with a friendly message.
 */
@Component({
  selector: 'sf-register',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './register.component.html',
  styleUrl: './auth.css',
})
export class RegisterComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    mobile: ['', [Validators.required, Validators.pattern(/^\d{10}$/)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    password: ['', [Validators.required, Validators.minLength(6)]],
  });

  showError(control: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[control];
    return c.invalid && c.touched;
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);

    const values = this.form.getRawValue();
    this.auth
      .register({
        fullName: values.fullName,
        mobile: values.mobile,
        email: values.email.trim() ? values.email.trim() : undefined,
        password: values.password,
      })
      .subscribe({
        next: () => {
          const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/account';
          void this.router.navigateByUrl(returnUrl);
        },
        error: (err: HttpErrorResponse) => {
          this.submitting.set(false);
          const body = err.error as ApiError | undefined;
          if (err.status === 409) {
            this.error.set('An account already exists for that mobile or email. Try signing in.');
          } else if (body?.details?.length) {
            this.error.set(body.details.join(' '));
          } else {
            this.error.set(body?.message ?? 'We could not create your account. Please try again.');
          }
        },
      });
  }
}
