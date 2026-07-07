import {
  Directive,
  ElementRef,
  Input,
  NgZone,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  inject,
} from '@angular/core';

/** Output formats for the animated value. */
export type CountFormat = 'inr' | 'int' | 'percent';

/**
 * Presentation-only "count up" for a numeric metric. Animates the element's
 * text from its previous value to the new one over a short duration using
 * {@code requestAnimationFrame} (run outside Angular so it never triggers change
 * detection). Rendering is never blocked — the final value is written
 * immediately for reduced-motion users. Formatting mirrors the dashboard's own
 * {@code inr()} / {@code count()} helpers so wiring stays visual-only.
 *
 * Usage: {@code <span [shifaCountUp]="value" countFormat="inr"></span>}
 */
@Directive({
  selector: '[shifaCountUp]',
  standalone: true,
})
export class CountUpDirective implements OnChanges, OnDestroy {
  private readonly el = inject(ElementRef<HTMLElement>);
  private readonly zone = inject(NgZone);

  @Input('shifaCountUp') value: number | null | undefined = 0;
  @Input() countFormat: CountFormat = 'int';
  @Input() countDuration = 900;

  private raf = 0;
  private current = 0;

  ngOnChanges(changes: SimpleChanges): void {
    const target = typeof this.value === 'number' ? this.value : 0;
    if (this.prefersReducedMotion() || changes['value']?.firstChange !== true && this.current === target) {
      // reduced motion, or unchanged: paint immediately
      if (this.prefersReducedMotion()) {
        this.current = target;
        this.paint(target);
        return;
      }
    }
    this.animateTo(target);
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.raf);
  }

  private animateTo(target: number): void {
    const start = this.current;
    const delta = target - start;
    if (delta === 0) {
      this.paint(target);
      return;
    }
    const duration = Math.max(200, this.countDuration);
    this.zone.runOutsideAngular(() => {
      cancelAnimationFrame(this.raf);
      const t0 = performance.now();
      const step = (now: number) => {
        const p = Math.min(1, (now - t0) / duration);
        // easeOutCubic
        const eased = 1 - Math.pow(1 - p, 3);
        this.current = start + delta * eased;
        this.paint(this.current);
        if (p < 1) {
          this.raf = requestAnimationFrame(step);
        } else {
          this.current = target;
          this.paint(target);
        }
      };
      this.raf = requestAnimationFrame(step);
    });
  }

  private paint(v: number): void {
    this.el.nativeElement.textContent = this.format(v);
  }

  private format(v: number): string {
    switch (this.countFormat) {
      case 'inr':
        return `\u20B9${Math.round(v).toLocaleString('en-IN')}`;
      case 'percent':
        return `${v.toFixed(2)}%`;
      default:
        return Math.round(v).toLocaleString('en-IN');
    }
  }

  private prefersReducedMotion(): boolean {
    return (
      typeof window !== 'undefined' &&
      window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
    );
  }
}
