/**
 * Pure checkout validation (Requirement 3.3, 3.4, 3.5).
 *
 * <p>Kept free of Angular/forms so it can be property-tested in isolation
 * (design correctness Property 15). The storefront checkout component uses this
 * predicate to decide whether submission is allowed, to flag each missing or
 * invalid field, and to retain the entered values.
 */

/** Raw checkout field values as entered by the customer. */
export interface CheckoutFormValues {
  customerName: string;
  mobile: string;
  addressLine: string;
  city: string;
  state: string;
  postalCode: string;
}

/** The checkout fields that are validated, in display order. */
export type CheckoutField = keyof CheckoutFormValues;

export const CHECKOUT_FIELDS: readonly CheckoutField[] = [
  'customerName',
  'mobile',
  'addressLine',
  'city',
  'state',
  'postalCode',
];

/** Human-readable labels used in validation messages. */
export const CHECKOUT_FIELD_LABELS: Record<CheckoutField, string> = {
  customerName: 'Full name',
  mobile: 'Mobile number',
  addressLine: 'Address',
  city: 'City',
  state: 'State',
  postalCode: 'Postal code',
};

/** Max lengths per Req 3.1 (name 1..100, address 1..250). */
export const MAX_NAME_LENGTH = 100;
export const MAX_ADDRESS_LENGTH = 250;

const MOBILE_PATTERN = /^\d{10}$/;
const POSTAL_PATTERN = /^\d{6}$/;

/** Result of validating a checkout form. */
export interface CheckoutValidationResult {
  valid: boolean;
  /** Fields that are missing/empty (Req 3.3). */
  missingFields: CheckoutField[];
  /** Per-field error message for every field that failed validation. */
  fieldErrors: Partial<Record<CheckoutField, string>>;
  /** Flat, human-readable messages (missing + invalid), in field order. */
  messages: string[];
}

function isBlank(value: string | null | undefined): boolean {
  return value === null || value === undefined || value.trim().length === 0;
}

/**
 * Validates checkout field values.
 *
 * <ul>
 *   <li>Every required field must be present/non-empty; each missing field is
 *       flagged (Req 3.3).</li>
 *   <li>Name must be 1..100 chars; address must be 1..250 chars (Req 3.1).</li>
 *   <li>Mobile must be exactly 10 digits (Req 3.4).</li>
 *   <li>Postal code must be exactly 6 digits (Req 3.5).</li>
 * </ul>
 *
 * The result is {@code valid} only when there are no field errors, so the
 * component can block submission while retaining the entered values.
 */
export function validateCheckout(values: CheckoutFormValues): CheckoutValidationResult {
  const missingFields: CheckoutField[] = [];
  const fieldErrors: Partial<Record<CheckoutField, string>> = {};
  const messages: string[] = [];

  for (const field of CHECKOUT_FIELDS) {
    const label = CHECKOUT_FIELD_LABELS[field];
    const raw = values[field];

    if (isBlank(raw)) {
      missingFields.push(field);
      const message = `${label} is required.`;
      fieldErrors[field] = message;
      messages.push(message);
      continue;
    }

    const value = raw.trim();

    if (field === 'customerName' && value.length > MAX_NAME_LENGTH) {
      const message = `${label} must be at most ${MAX_NAME_LENGTH} characters.`;
      fieldErrors[field] = message;
      messages.push(message);
    } else if (field === 'addressLine' && value.length > MAX_ADDRESS_LENGTH) {
      const message = `${label} must be at most ${MAX_ADDRESS_LENGTH} characters.`;
      fieldErrors[field] = message;
      messages.push(message);
    } else if (field === 'mobile' && !MOBILE_PATTERN.test(value)) {
      const message = 'Mobile number must be exactly 10 digits.';
      fieldErrors[field] = message;
      messages.push(message);
    } else if (field === 'postalCode' && !POSTAL_PATTERN.test(value)) {
      const message = 'Postal code must be exactly 6 digits.';
      fieldErrors[field] = message;
      messages.push(message);
    }
  }

  return {
    valid: Object.keys(fieldErrors).length === 0,
    missingFields,
    fieldErrors,
    messages,
  };
}
