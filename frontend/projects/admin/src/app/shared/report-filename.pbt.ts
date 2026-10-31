import * as fc from 'fast-check';

import { filenameFromContentDisposition } from './blob-download.util';
import {
  asOfFilename,
  labelledFilename,
  rangePart,
  reportFilename,
  slugify,
} from './report-filename.util';

/**
 * Report download naming. The example vectors below are the same ones asserted
 * by the backend `ReportFilenamesTest`, which keeps the two implementations
 * from drifting; the properties check that no input can produce an unsafe name.
 */
describe('report filenames', () => {
  const SAFE = /^[a-z0-9._-]+$/;

  describe('matches the server examples', () => {
    it('names a report after its title and date range', () => {
      expect(reportFilename('Daily Sales Report', '2026-10-02', '2026-10-31', 'pdf')).toBe(
        'shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf',
      );
    });

    it('describes open-ended and single-day ranges', () => {
      expect(rangePart(null, null)).toBe('all-time');
      expect(rangePart('2026-10-02', null)).toBe('from-2026-10-02');
      expect(rangePart(null, '2026-10-31')).toBe('up-to-2026-10-31');
      expect(rangePart('2026-10-02', '2026-10-02')).toBe('2026-10-02');
      expect(rangePart('', '')).toBe('all-time');
      expect(reportFilename('Customer-wise Report', null, null, 'xlsx')).toBe(
        'shifa-customer-wise-report_all-time.xlsx',
      );
    });

    it('turns symbols and accents into plain words', () => {
      expect(slugify('Salesperson \u00d7 Product Report')).toBe('salesperson-product-report');
      expect(slugify('Returns & Refunds Report')).toBe('returns-refunds-report');
      expect(slugify('Pending from Courier')).toBe('pending-from-courier');
      expect(slugify('Caf\u00e9 \u00d1and\u00fa')).toBe('cafe-nandu');
    });

    it('falls back when nothing usable is left', () => {
      expect(slugify(null)).toBe('report');
      expect(slugify('   ')).toBe('report');
      expect(slugify('\u0906\u0936\u093e')).toBe('report');
      expect(slugify('\u0906\u0936\u093e', 'user-17')).toBe('user-17');
      expect(reportFilename(null, '2026-10-02', '2026-10-31', 'csv')).toBe(
        'shifa-report_2026-10-02_to_2026-10-31.csv',
      );
    });

    it('sanitises the extension', () => {
      expect(reportFilename('Orders', '2026-10-02', '2026-10-31', 'XLSX')).toMatch(/\.xlsx$/);
      expect(reportFilename('Orders', '2026-10-02', '2026-10-31', '../x')).toMatch(/\.x$/);
      expect(reportFilename('Orders', '2026-10-02', '2026-10-31', '')).toMatch(/\.bin$/);
    });
  });

  describe('client-side CSV names', () => {
    it('dates a point-in-time list by the day it was taken', () => {
      expect(asOfFilename('Partial Settlements Follow-up', '2026-10-31', 'csv')).toBe(
        'shifa-partial-settlements-follow-up_as-of-2026-10-31.csv',
      );
    });

    it('accepts a caller-chosen label', () => {
      expect(labelledFilename('Salespeople Performance', 'month', 'xlsx')).toBe(
        'shifa-salespeople-performance_month.xlsx',
      );
    });

    it('gives different reports and ranges different names', () => {
      const names = new Set([
        reportFilename('Team Performance', '2026-10-01', '2026-10-31', 'csv'),
        reportFilename('Team Performance', '2026-10-01', '2026-10-07', 'csv'),
        reportFilename('Remittance Batch 12 Details', '2026-10-20', '2026-10-20', 'csv'),
        reportFilename('Remittance Batch 13 Details', '2026-10-20', '2026-10-20', 'csv'),
      ]);
      expect(names.size).toBe(4);
    });
  });

  describe('safety properties', () => {
    it('never produces an unsafe name, whatever the title', () => {
      fc.assert(
        fc.property(fc.string(), fc.string(), (title, ext) => {
          const name = reportFilename(title, '2026-10-02', '2026-10-31', ext);
          expect(name).toMatch(SAFE);
          expect(name).not.toContain('..');
          expect(name.startsWith('shifa-')).toBe(true);
        }),
        { numRuns: 300 },
      );
    });

    it('never produces an unsafe slug from arbitrary unicode', () => {
      fc.assert(
        fc.property(fc.string({ unit: 'binary' }), (text) => {
          const slug = slugify(text);
          expect(slug).toMatch(/^[a-z0-9-]+$/);
          expect(slug.length).toBeLessThanOrEqual(80);
          expect(slug.startsWith('-') || slug.endsWith('-')).toBe(false);
        }),
        { numRuns: 300 },
      );
    });
  });

  describe('Content-Disposition parsing', () => {
    it('reads a quoted filename', () => {
      expect(
        filenameFromContentDisposition(
          'attachment; filename="shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf"',
        ),
      ).toBe('shifa-daily-sales-report_2026-10-02_to_2026-10-31.pdf');
    });

    it('reads a bare filename', () => {
      expect(filenameFromContentDisposition('attachment; filename=shifa-orders_all-time.csv')).toBe(
        'shifa-orders_all-time.csv',
      );
    });

    it('prefers the RFC 5987 form and decodes it', () => {
      expect(
        filenameFromContentDisposition(
          `attachment; filename="fallback.pdf"; filename*=UTF-8''shifa-%C3%A9.pdf`,
        ),
      ).toBe('shifa-\u00e9.pdf');
    });

    it('returns null when there is no usable filename', () => {
      expect(filenameFromContentDisposition(null)).toBeNull();
      expect(filenameFromContentDisposition('')).toBeNull();
      expect(filenameFromContentDisposition('inline')).toBeNull();
      expect(filenameFromContentDisposition('attachment; filename=""')).toBeNull();
    });

    it('round-trips every generated report name', () => {
      fc.assert(
        fc.property(fc.string(), (title) => {
          const name = reportFilename(title, '2026-10-02', '2026-10-31', 'pdf');
          expect(filenameFromContentDisposition(`attachment; filename="${name}"`)).toBe(name);
        }),
        { numRuns: 200 },
      );
    });
  });
});
