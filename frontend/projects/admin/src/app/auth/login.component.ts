import { NgTemplateOutlet } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService, MfaChallenge } from 'core';
import * as QRCode from 'qrcode';

/**
 * Admin/staff login with optional TOTP multi-factor (Google Authenticator).
 *
 * <p>Three steps, driven by {@link step}:
 * <ol>
 *   <li>{@code password} — username + password. On success either the session is
 *       established (no MFA) or the server returns an MFA challenge.</li>
 *   <li>{@code code} — the user is enrolled; enter the current 6-digit
 *       authenticator code ({@code /api/auth/mfa/verify}).</li>
 *   <li>{@code enroll} — MFA is on but the user has not enrolled; scan the QR (or
 *       type the secret) into the authenticator app, then confirm the first code
 *       ({@code /api/auth/mfa/enroll/confirm}).</li>
 * </ol>
 * After a completed login it navigates to the {@code returnUrl} (or the
 * change-password screen when an admin reset forces it).
 */
@Component({
  selector: 'admin-login',
  imports: [ReactiveFormsModule, NgTemplateOutlet],
  templateUrl: './login.component.html',
  styleUrl: './login.component.css',
})
export class LoginComponent {
  private readonly fb = inject(FormBuilder);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  protected readonly error = signal<string | null>(null);
  protected readonly submitting = signal(false);

  /** Which step of the login flow is on screen. */
  protected readonly step = signal<'password' | 'code' | 'enroll'>('password');

  /** The pending MFA challenge (ticket + enrollment payload), when in an MFA step. */
  private readonly challenge = signal<MfaChallenge | null>(null);

  /** The Base32 secret shown for manual authenticator entry (enrollment only). */
  protected readonly manualSecret = signal<string | null>(null);

  /** Data-URI of the rendered enrollment QR code, or null if rendering failed. */
  protected readonly qrDataUri = signal<string | null>(null);

  protected readonly form = this.fb.nonNullable.group({
    username: ['', Validators.required],
    password: ['', Validators.required],
  });

  /** The 6-digit authenticator code entered during verify/enroll. */
  protected readonly codeForm = this.fb.nonNullable.group({
    code: ['', [Validators.required, Validators.pattern(/^\d{6}$/)]],
  });

  /** Step 1 — submit username + password. */
  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    this.auth.login(this.form.getRawValue()).subscribe({
      next: (result) => {
        if (result.kind === 'authenticated') {
          this.completeLogin();
          return;
        }
        this.beginMfa(result.challenge);
      },
      error: () => {
        this.submitting.set(false);
        this.error.set('Invalid username or password.');
      },
    });
  }

  /** Step 2/3 — submit the authenticator code (verify for enrolled, confirm for enrollment). */
  submitCode(): void {
    const challenge = this.challenge();
    if (!challenge || this.codeForm.invalid || this.submitting()) {
      this.codeForm.markAllAsTouched();
      return;
    }
    this.submitting.set(true);
    this.error.set(null);
    const code = this.codeForm.getRawValue().code.trim();
    const request$ =
      challenge.status === 'MFA_ENROLL'
        ? this.auth.confirmMfaEnrollment(challenge.mfaTicket, code)
        : this.auth.verifyMfa(challenge.mfaTicket, code);
    request$.subscribe({
      next: () => this.completeLogin(),
      error: (err) => {
        this.submitting.set(false);
        // A stale/expired ticket (5-min TTL) needs a fresh password sign-in.
        if (err?.status === 401 && err?.error?.code === 'INVALID_TOKEN') {
          this.resetToPassword('Your session timed out. Please sign in again.');
          return;
        }
        this.error.set('That code is incorrect or expired. Enter the current code from your app.');
      },
    });
  }

  /** Abandons the MFA step and returns to the password screen. */
  cancelMfa(): void {
    this.resetToPassword(null);
  }

  private beginMfa(challenge: MfaChallenge): void {
    this.submitting.set(false);
    this.challenge.set(challenge);
    this.codeForm.reset({ code: '' });
    this.error.set(null);
    if (challenge.status === 'MFA_ENROLL') {
      this.manualSecret.set(challenge.secret ?? null);
      this.step.set('enroll');
      void this.renderQr(challenge.otpauthUri ?? null);
    } else {
      this.manualSecret.set(null);
      this.qrDataUri.set(null);
      this.step.set('code');
    }
  }

  private async renderQr(otpauthUri: string | null): Promise<void> {
    if (!otpauthUri) {
      this.qrDataUri.set(null);
      return;
    }
    try {
      const dataUri = await QRCode.toDataURL(otpauthUri, { width: 220, margin: 1 });
      this.qrDataUri.set(dataUri);
    } catch {
      // QR rendering failed — the manual secret is the reliable fallback.
      this.qrDataUri.set(null);
    }
  }

  private completeLogin(): void {
    // An admin-reset account signs in with the temporary password and must
    // choose a new one before using the app.
    if (this.auth.mustChangePassword()) {
      void this.router.navigateByUrl('/change-password');
      return;
    }
    const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/dashboard';
    void this.router.navigateByUrl(returnUrl);
  }

  private resetToPassword(message: string | null): void {
    this.step.set('password');
    this.challenge.set(null);
    this.manualSecret.set(null);
    this.qrDataUri.set(null);
    this.codeForm.reset({ code: '' });
    this.form.controls.password.reset('');
    this.submitting.set(false);
    this.error.set(message);
  }
}
