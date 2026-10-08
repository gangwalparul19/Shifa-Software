import { NgStyle } from '@angular/common';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { GuidedTourService } from './guided-tour.service';

/** Pixel rect used to position the spotlight cutout + tooltip. */
interface Rect {
  top: number;
  left: number;
  width: number;
  height: number;
}

/**
 * Renders the active {@link GuidedTourService} step as a dimmed overlay with a
 * spotlight "hole" around the target element and a small tooltip card with
 * Back/Next/Skip — a minimal, dependency-free coach-mark tour (enhancement:
 * "First-run guided tour + contextual help tooltips"). Mounted once at the
 * shell root; renders nothing when no tour is active.
 *
 * <p>Re-measures the target element on window resize/scroll so the spotlight
 * tracks it; if the selector can't be found (e.g. the page changed under it),
 * the step is skipped automatically rather than showing a broken overlay.
 */
@Component({
  selector: 'admin-guided-tour',
  standalone: true,
  imports: [NgStyle],
  templateUrl: './guided-tour.component.html',
  styleUrl: './guided-tour.component.css',
})
export class GuidedTourComponent {
  protected readonly tour = inject(GuidedTourService);

  protected readonly rect = signal<Rect | null>(null);

  protected readonly currentStep = computed(() => {
    const steps = this.tour.steps();
    return steps ? steps[this.tour.index()] ?? null : null;
  });

  protected readonly isLast = computed(() => {
    const steps = this.tour.steps();
    return !!steps && this.tour.index() >= steps.length - 1;
  });

  protected readonly total = computed(() => this.tour.steps()?.length ?? 0);
  protected readonly position = computed(() => this.tour.index() + 1);

  private readonly resizeHandler = () => this.measure();

  constructor() {
    effect(() => {
      const step = this.currentStep();
      // Re-measure whenever the step changes; retry briefly in case the target
      // hasn't rendered yet (e.g. right after route navigation).
      if (step) {
        this.measureWithRetry(step.selector);
      } else {
        this.rect.set(null);
      }
    });
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', this.resizeHandler);
      window.addEventListener('scroll', this.resizeHandler, true);
    }
  }

  private measureWithRetry(selector: string, attemptsLeft = 5): void {
    const el = document.querySelector(selector);
    const visible = el && this.isVisible(el);
    if (visible) {
      el.scrollIntoView({ block: 'center', behavior: 'smooth' });
      setTimeout(() => this.measure(), 150);
      return;
    }
    if (attemptsLeft > 0) {
      setTimeout(() => this.measureWithRetry(selector, attemptsLeft - 1), 200);
    } else {
      // Target never appeared, or is hidden (e.g. bottom tabs on desktop where
      // CSS hides them ≥992px) — skip this step so the tour never gets stuck.
      this.tour.next();
    }
  }

  private isVisible(el: Element): boolean {
    const r = el.getBoundingClientRect();
    return r.width > 0 && r.height > 0;
  }

  private measure(): void {
    const step = this.currentStep();
    if (!step) {
      this.rect.set(null);
      return;
    }
    const el = document.querySelector(step.selector);
    if (!el || !this.isVisible(el)) {
      this.rect.set(null);
      return;
    }
    const r = el.getBoundingClientRect();
    this.rect.set({ top: r.top, left: r.left, width: r.width, height: r.height });
  }

  /** Inline style for the tooltip card, placed below/above/beside the spotlighted rect. */
  tooltipStyle(): Record<string, string> {
    const r = this.rect();
    const step = this.currentStep();
    if (!r || !step) {
      return { display: 'none' };
    }
    const margin = 12;
    const placement = step.placement ?? (r.top < window.innerHeight / 2 ? 'bottom' : 'top');
    const cardWidth = 300;

    let top: number;
    let left = Math.min(Math.max(r.left, 12), Math.max(12, window.innerWidth - cardWidth - 12));

    if (placement === 'bottom') {
      top = r.top + r.height + margin;
    } else if (placement === 'top') {
      top = r.top - margin;
    } else {
      top = r.top;
    }

    return {
      top: `${Math.max(12, top)}px`,
      left: `${left}px`,
      transform: placement === 'top' ? 'translateY(-100%)' : 'none',
    };
  }

  spotlightStyle(): Record<string, string> {
    const r = this.rect();
    if (!r) {
      return { display: 'none' };
    }
    const pad = 6;
    return {
      top: `${r.top - pad}px`,
      left: `${r.left - pad}px`,
      width: `${r.width + pad * 2}px`,
      height: `${r.height + pad * 2}px`,
    };
  }

  next(): void {
    this.tour.next();
  }

  back(): void {
    this.tour.back();
  }

  skip(): void {
    this.tour.finish();
  }
}
