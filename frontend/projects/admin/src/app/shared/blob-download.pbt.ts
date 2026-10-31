import * as fc from 'fast-check';
import { resolveBlobFilename } from './blob-download.util';

describe('blob download filename fallback (PBT)', () => {
  it('always returns a non-empty safe filename', () => {
    fc.assert(
      fc.property(
        fc.string(),
        fc
          .array(fc.constantFrom('a', 'b', '1', '2', '-', '_'), { minLength: 1, maxLength: 20 })
          .map((chars) => chars.join('')),
        (filename, fallback) => {
          const resolved = resolveBlobFilename(filename, fallback);
          expect(resolved.length).toBeGreaterThan(0);
          expect(resolved).not.toMatch(/[\\/:*?"<>|]/);
        },
      ),
    );
  });

  it('uses the fallback for blank or path-only names', () => {
    fc.assert(
      fc.property(fc.constantFrom('', ' ', '\\', '/', '///'), (filename) => {
        expect(resolveBlobFilename(filename, 'download.bin')).toBe('download.bin');
      }),
    );
  });
});
