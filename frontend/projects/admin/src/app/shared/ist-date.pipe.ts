import { DatePipe } from '@angular/common';
import { Pipe, PipeTransform } from '@angular/core';

/**
 * Formats a timestamp in India Standard Time (Asia/Kolkata, {@code +05:30}),
 * regardless of the viewer's device/browser timezone. The business runs entirely
 * in India, so every displayed time must be IST — this is the single, app-wide
 * date/time formatter.
 *
 * <p>It wraps Angular's {@link DatePipe} but pins {@code timezone: '+0530'}, so a
 * device set to any zone still shows the IST wall-clock. The backend now emits
 * timestamps WITH the {@code +05:30} offset (see {@code JacksonTimeConfig}), so
 * the underlying instant is unambiguous; this pipe then renders it in IST.
 *
 * <p>Usage:
 * <pre>
 *   {{ order.createdAt | istDate }}                    → 22 Sep 2026, 18:20
 *   {{ order.createdAt | istDate: 'dd MMM yyyy' }}      → 22 Sep 2026
 *   {{ e.changedAt | istDate: 'short' }}               → 22/09/26, 6:20 pm
 * </pre>
 *
 * Nullish / unparseable input renders an empty string.
 */
@Pipe({ name: 'istDate', standalone: true })
export class IstDatePipe implements PipeTransform {
  // Self-contained DatePipe (en-US locale data is always registered) so the pipe
  // needs no DI provider wiring; '+0530' below is what pins the render to IST.
  private readonly datePipe = new DatePipe('en-US');

  transform(value: string | number | Date | null | undefined, format = 'dd MMM yyyy, HH:mm'): string {
    if (value === null || value === undefined || value === '') {
      return '';
    }
    // '+0530' pins the render zone to IST no matter the device timezone.
    return this.datePipe.transform(value, format, '+0530') ?? '';
  }
}
