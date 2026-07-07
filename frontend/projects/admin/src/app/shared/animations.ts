import { animate, animateChild, group, query, style, transition, trigger } from '@angular/animations';

/**
 * Presentation-only route transition for the shell's {@code <router-outlet>}.
 * New views fade in with a slight upward translate; the leaving view fades out
 * quickly so the pages never overlap awkwardly. Reduced-motion users get an
 * effectively instant swap because the global stylesheet clamps animation
 * durations to ~0ms under {@code prefers-reduced-motion}.
 */
export const routeFade = trigger('routeFade', [
  transition('* <=> *', [
    query(
      ':enter',
      [style({ opacity: 0, transform: 'translateY(10px)' })],
      { optional: true },
    ),
    group([
      query(
        ':leave',
        [animate('140ms ease', style({ opacity: 0 }))],
        { optional: true },
      ),
      query(
        ':enter',
        [
          animate(
            '360ms cubic-bezier(0.22, 1, 0.36, 1)',
            style({ opacity: 1, transform: 'translateY(0)' }),
          ),
          animateChild(),
        ],
        { optional: true },
      ),
    ]),
  ]),
]);
