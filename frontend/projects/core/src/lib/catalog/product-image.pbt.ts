import * as fc from 'fast-check';
import { ProductImage } from '../models/product.model';
import {
  PLACEHOLDER_PRODUCT_IMAGE,
  selectProductImage,
} from './product-image.util';

/**
 * Feature: shifa-herbal-remedies, Property 17: Placeholder image when no
 * published image. For any product, the display image is the placeholder if and
 * only if the product has no published image; when at least one published image
 * exists, the selected image is a published one (never the placeholder and never
 * an unpublished image), specifically the published image with the lowest sort
 * order.
 *
 * Validates: Requirements 1.4
 */
describe('selectProductImage (PBT)', () => {
  // Object keys are constrained to be distinct from the placeholder so we can
  // unambiguously assert "placeholder iff no published image".
  const objectKey = fc
    .string({ minLength: 1, maxLength: 20 })
    .filter((s) => s !== PLACEHOLDER_PRODUCT_IMAGE);

  const image: fc.Arbitrary<ProductImage> = fc.record({
    id: fc.integer({ min: 1, max: 100000 }),
    objectKey,
    published: fc.boolean(),
    sortOrder: fc.integer({ min: -50, max: 50 }),
  });

  const images = fc.array(image, { maxLength: 12 });

  // Feature: shifa-herbal-remedies, Property 17: Placeholder image when no published image
  it('returns the placeholder exactly when there is no published image', () => {
    fc.assert(
      fc.property(images, (list) => {
        const published = list.filter((i) => i.published);
        const result = selectProductImage(list);

        if (published.length === 0) {
          expect(result).toBe(PLACEHOLDER_PRODUCT_IMAGE);
        } else {
          // Never the placeholder, and always a *published* image's key.
          expect(result).not.toBe(PLACEHOLDER_PRODUCT_IMAGE);
          expect(published.map((i) => i.objectKey)).toContain(result);

          // Specifically, the published image with the lowest sortOrder.
          const minSort = Math.min(...published.map((i) => i.sortOrder));
          const expected = published.find((i) => i.sortOrder === minSort)!;
          expect(result).toBe(expected.objectKey);
        }
      }),
      { numRuns: 200 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 17: Placeholder image when no published image
  it('treats undefined/empty image lists as no published image', () => {
    fc.assert(
      fc.property(fc.constantFrom(undefined, null, [] as ProductImage[]), (empty) => {
        expect(selectProductImage(empty)).toBe(PLACEHOLDER_PRODUCT_IMAGE);
      }),
      { numRuns: 100 },
    );
  });

  // Feature: shifa-herbal-remedies, Property 17: Placeholder image when no published image
  it('honours a custom placeholder when no published image exists', () => {
    const custom = '/custom/placeholder.png';
    fc.assert(
      fc.property(
        fc.array(
          image.map((i) => ({ ...i, published: false })),
          { maxLength: 8 },
        ),
        (unpublished) => {
          expect(selectProductImage(unpublished, custom)).toBe(custom);
        },
      ),
      { numRuns: 100 },
    );
  });
});
