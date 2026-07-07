import * as fc from 'fast-check';
import {
  CheckoutFormValues,
  MAX_ADDRESS_LENGTH,
  MAX_NAME_LENGTH,
  validateCheckout,
} from './checkout-validation';

/**
 * Feature: shifa-herbal-remedies, Property 15: Checkout validation. For any set
 * of checkout field values, the validation accepts the submission if and only
 * if every required field is present, the mobile number is exactly 10 digits,
 * and the postal code is exactly 6 digits; otherwise it is rejected, each
 * missing field is flagged, and an invalid mobile or postal code is reported.
 *
 * Validates: Requirements 3.3, 3.4, 3.5
 */
describe('validateCheckout (PBT)', () => {
  const digits = (n: number): fc.Arbitrary<string> =>
    fc.array(fc.integer({ min: 0, max: 9 }), { minLength: n, maxLength: n }).map((a) => a.join(''));

  // Non-blank text within a max length (after trimming).
  const text = (max: number): fc.Arbitrary<string> =>
    fc
      .string({ minLength: 1, maxLength: max })
      .map((s) => s.trim())
      .filter((s) => s.length >= 1 && s.length <= max);

  const validValues: fc.Arbitrary<CheckoutFormValues> = fc.record({
    customerName: text(MAX_NAME_LENGTH),
    mobile: digits(10),
    addressLine: text(MAX_ADDRESS_LENGTH),
    city: text(100),
    state: text(100),
    postalCode: digits(6),
  });

  // Feature: shifa-herbal-remedies, Property 15: Checkout validation
  it('accepts well-formed checkout values with no errors', () => {
    fc.assert(
      fc.property(validValues, (values) => {
        const result = validateCheckout(values);
        expect(result.valid).toBe(true);
        expect(result.missingFields).toHaveLength(0);
        expect(result.messages).toHaveLength(0);
        expect(Object.keys(result.fieldErrors)).toHaveLength(0);
      }),
      { numRuns: 200 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 15: Checkout validation
  it('flags every missing/blank required field (Req 3.3)', () => {
    const blankish = fc.constantFrom('', '   ', '\t', '\n  ');
    fc.assert(
      fc.property(
        validValues,
        fc.subarray(
          ['customerName', 'mobile', 'addressLine', 'city', 'state', 'postalCode'] as const,
          { minLength: 1 },
        ),
        blankish,
        (base, fieldsToBlank, blank) => {
          const values: CheckoutFormValues = { ...base };
          for (const f of fieldsToBlank) {
            values[f] = blank;
          }
          const result = validateCheckout(values);
          expect(result.valid).toBe(false);
          for (const f of fieldsToBlank) {
            expect(result.missingFields).toContain(f);
            expect(result.fieldErrors[f]).toBeDefined();
          }
        },
      ),
      { numRuns: 200 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 15: Checkout validation
  it('rejects a present mobile that is not exactly 10 digits (Req 3.4)', () => {
    const badMobile = fc
      .string({ minLength: 1, maxLength: 15 })
      .map((s) => s.trim())
      .filter((s) => s.length >= 1 && !/^\d{10}$/.test(s));
    fc.assert(
      fc.property(validValues, badMobile, (base, mobile) => {
        const result = validateCheckout({ ...base, mobile });
        expect(result.valid).toBe(false);
        expect(result.fieldErrors.mobile).toBeDefined();
        // A non-blank invalid mobile is a format error, not a "missing" field.
        expect(result.missingFields).not.toContain('mobile');
      }),
      { numRuns: 200 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 15: Checkout validation
  it('rejects a present postal code that is not exactly 6 digits (Req 3.5)', () => {
    const badPostal = fc
      .string({ minLength: 1, maxLength: 15 })
      .map((s) => s.trim())
      .filter((s) => s.length >= 1 && !/^\d{6}$/.test(s));
    fc.assert(
      fc.property(validValues, badPostal, (base, postalCode) => {
        const result = validateCheckout({ ...base, postalCode });
        expect(result.valid).toBe(false);
        expect(result.fieldErrors.postalCode).toBeDefined();
        expect(result.missingFields).not.toContain('postalCode');
      }),
      { numRuns: 200 },
    );
  });
});
