import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { AccountApi } from './account-api.service';

/**
 * Profile view/edit (Phase B): the customer updates their display name, email,
 * and mobile. Role and username are read-only (never editable here). Backed by
 * {@code /api/account/profile}.
 */
@Component({
  selector: 'sf-account-profile',
  imports: [ReactiveFormsModule, RouterLink],
  templateUrl: './account-profile.component.html',
  styleUrl: './account.css',
})
export class AccountProfileComponent {
  private readonly fb = inject(FormBuilder);
  private readonly accountApi = inject(AccountApi);

  protected readonly loading = signal(true);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saved = signal(false);
  protected readonly username = signal<string>('');

  protected readonly form = this.fb.nonNullable.group({
    fullName: ['', [Validators.required, Validators.maxLength(150)]],
    email: ['', [Validators.email, Validators.maxLength(150)]],
    mobile: ['', [Validators.pattern(/^\d{10}$/)]],
  });

  constructor() {
    this.accountApi.getProfile().subscribe({
      next: (profile) => {
        this.username.set(profile.username);
        this.form.patchValue({
          fullName: profile.fullName,
          email: profile.email ?? '',
          mobile: profile.mobile ?? '',
        });
        this.loading.set(false);
      },
      error: () => {
        this.error.set('We could not load your profile.');
        this.loading.set(false);
      },
    });
  }

  showError(control: keyof typeof this.form.controls): boolean {
    const c = this.form.controls[control];
    return c.invalid && c.touched;
  }

  save(): void {
    if (this.form.invalid || this.saving()) {
      this.form.markAllAsTouched();
      return;
    }
    const values = this.form.getRawValue();
    this.saving.set(true);
    this.saved.set(false);
    this.error.set(null);
    this.accountApi
      .updateProfile({
        fullName: values.fullName.trim(),
        email: values.email.trim() || undefined,
        mobile: values.mobile.trim() || undefined,
      })
      .subscribe({
        next: () => {
          this.saving.set(false);
          this.saved.set(true);
        },
        error: () => {
          this.saving.set(false);
          this.error.set('We could not save your profile.');
        },
      });
  }
}
