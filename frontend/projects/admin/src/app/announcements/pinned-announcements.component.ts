import { Component, input } from '@angular/core';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { Announcement, announcementIcon } from './announcements.model';

type Tone = 'info' | 'success' | 'warning' | 'danger';

/**
 * Announcements an admin has pinned to the top of the home screen.
 *
 * <p>Every active announcement already shows as a thin, dismissible banner, which staff tend to
 * close once and forget. A pinned one is a larger card that cannot be dismissed, so it stays in
 * front of everyone until the admin unpins or hides it. Rendering only: the shell decides which
 * announcements to pass in and on which screen to show them.
 */
@Component({
  selector: 'admin-pinned-announcements',
  imports: [IstDatePipe],
  template: `
    @if (announcements().length > 0) {
      <section class="pinned-ann" aria-label="Announcements">
        @for (a of announcements(); track a.id) {
          <article class="pinned-ann__card" [attr.data-tone]="tone(a.severity)" role="status">
            <span class="pinned-ann__icon" aria-hidden="true">
              <i class="ti {{ icon(a.severity) }}"></i>
            </span>
            <div class="pinned-ann__body">
              <div class="pinned-ann__eyebrow">Announcement</div>
              <div class="pinned-ann__text">{{ a.message }}</div>
              <div class="pinned-ann__meta">
                {{ a.createdByName || 'Admin' }} · {{ a.createdAt | istDate: 'd MMM yyyy' }}
              </div>
            </div>
          </article>
        }
      </section>
    }
  `,
  styles: `
    .pinned-ann {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      margin-top: 0.75rem;
    }
    .pinned-ann__card {
      --tone: #206bc4;
      --tone-soft: #eaf2fb;
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
      padding: 0.75rem 1rem;
      background: var(--tone-soft);
      border: 1px solid color-mix(in srgb, var(--tone) 25%, transparent);
      border-left: 5px solid var(--tone);
      border-radius: 0.5rem;
    }
    .pinned-ann__card[data-tone='success'] {
      --tone: #1f5d3f;
      --tone-soft: #e8f3ed;
    }
    .pinned-ann__card[data-tone='warning'] {
      --tone: #b45309;
      --tone-soft: #fff4e0;
    }
    .pinned-ann__card[data-tone='danger'] {
      --tone: #c0392b;
      --tone-soft: #fdecea;
    }
    .pinned-ann__icon {
      flex: 0 0 auto;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 2.25rem;
      height: 2.25rem;
      border-radius: 50%;
      background: var(--tone);
      color: #fff;
      font-size: 1.15rem;
    }
    .pinned-ann__body {
      min-width: 0;
      flex: 1 1 auto;
    }
    .pinned-ann__eyebrow {
      font-size: 0.7rem;
      font-weight: 700;
      letter-spacing: 0.06em;
      text-transform: uppercase;
      color: var(--tone);
    }
    .pinned-ann__text {
      font-size: 1rem;
      font-weight: 600;
      line-height: 1.4;
      color: #1d273b;
      white-space: pre-line;
      overflow-wrap: anywhere;
    }
    .pinned-ann__meta {
      margin-top: 0.15rem;
      font-size: 0.75rem;
      color: #667085;
    }
  `,
})
export class PinnedAnnouncementsComponent {
  /** The pinned, active announcements to show (newest first). */
  readonly announcements = input<Announcement[]>([]);

  protected readonly icon = announcementIcon;

  /** Maps any server value onto the four known tones so an odd value cannot break the styling. */
  protected tone(severity: string): Tone {
    return severity === 'success' || severity === 'warning' || severity === 'danger' ? severity : 'info';
  }
}
