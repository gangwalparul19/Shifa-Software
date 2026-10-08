import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * Password policy shared across the admin app's password forms (forced change
 * after an admin reset, and any voluntary change). Mirrors the backend
 * {@code ChangePasswordRequest.PASSWORD_PATTERN} so the client and server agree:
 * minimum 8 characters with at least one uppercase letter, one number, and one
 * special (non-alphanumeric) character.
 */
export const PASSWORD_PATTERN = /^(?=.*[A-Z])(?=.*\d)(?=.*[^A-Za-z0-9]).{8,}$/;

/** The fixed temporary password an admin reset sets (users must change it). */
export const TEMPORARY_PASSWORD = 'Welcome@123';

/** Human-readable requirements shown next to the password field. */
export const PASSWORD_REQUIREMENTS: readonly string[] = [
  'At least 8 characters',
  'At least one uppercase letter',
  'At least one number',
  'At least one special character',
];

/**
 * Reactive-form validator enforcing the password policy. Returns a
 * {@code { passwordPolicy: true }} error when the value is non-empty and does
 * not satisfy the pattern (empty is left to {@code Validators.required}).
 */
export function passwordPolicyValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    if (!value) {
      return null;
    }
    return PASSWORD_PATTERN.test(value) ? null : { passwordPolicy: true };
  };
}

/**
 * Group-level validator that flags a {@code { passwordMismatch: true }} error on
 * the group when the two named controls differ (both non-empty). Attach to a
 * {@code FormGroup} containing a new-password + confirm-password control.
 */
export function passwordsMatchValidator(
  passwordKey = 'newPassword',
  confirmKey = 'confirmPassword',
): ValidatorFn {
  return (group: AbstractControl): ValidationErrors | null => {
    const password = group.get(passwordKey)?.value;
    const confirm = group.get(confirmKey)?.value;
    if (!password || !confirm) {
      return null;
    }
    return password === confirm ? null : { passwordMismatch: true };
  };
}
