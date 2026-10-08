import { Injectable, signal } from '@angular/core';

/** One step of a guided tour: highlights a DOM element and shows a tip near it. */
export interface TourStep {
  /** CSS selector for the element to spotlight (must be visible on screen). */
  selector: string;
  title: string;
  body: string;
  /** Preferred tooltip placement relative to the target; falls back automatically if it doesn't fit. */
  placement?: 'top' | 'bottom' | 'left' | 'right';
}

const DISMISSED_KEY = 'shifa.tourSeen.v1';

/**
 * First-run guided tour + contextual help (enhancement: "First-run guided
 * tour + contextual help tooltips"). A tiny, dependency-free spotlight tour:
 * given an ordered list of {@link TourStep}s, {@link start} walks the user
 * through them one at a time with Next/Back/Skip, rendered by
 * {@code GuidedTourComponent} (mounted once at the shell root).
 *
 * <p>Each named tour is only auto-started once per browser (tracked in
 * localStorage by tour id) so returning users are never interrupted again —
 * but any tour can be replayed on demand (e.g. a "Take the tour" help menu
 * item) via {@link start} regardless of dismissal state.
 */
@Injectable({ providedIn: 'root' })
export class GuidedTourService {
  /** The currently running tour's steps, or null when no tour is active. */
  readonly steps = signal<TourStep[] | null>(null);
  /** Index of the active step within {@link steps}. */
  readonly index = signal(0);

  /** Whether a given named tour has already been shown/dismissed on this device. */
  hasSeen(tourId: string): boolean {
    if (typeof localStorage === 'undefined') {
      return true;
    }
    try {
      const seen = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]');
      return Array.isArray(seen) && seen.includes(tourId);
    } catch {
      return false;
    }
  }

  /** Marks a named tour as seen, so {@link startIfUnseen} won't auto-start it again. */
  markSeen(tourId: string): void {
    if (typeof localStorage === 'undefined') {
      return;
    }
    try {
      const seen: string[] = JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]');
      if (!seen.includes(tourId)) {
        localStorage.setItem(DISMISSED_KEY, JSON.stringify([...seen, tourId]));
      }
    } catch {
      // Best-effort only — a tour re-showing once is harmless.
    }
  }

  /** Starts a tour only if it has never been shown on this device before (first-run behaviour). */
  startIfUnseen(tourId: string, steps: TourStep[]): void {
    if (this.hasSeen(tourId) || steps.length === 0) {
      return;
    }
    this.start(tourId, steps);
  }

  /** Starts (or restarts) a tour immediately, regardless of prior dismissal. */
  start(tourId: string, steps: TourStep[]): void {
    this.activeTourId = tourId;
    this.steps.set(steps);
    this.index.set(0);
  }

  private activeTourId: string | null = null;

  next(): void {
    const steps = this.steps();
    if (!steps) {
      return;
    }
    if (this.index() >= steps.length - 1) {
      this.finish();
      return;
    }
    this.index.update((i) => i + 1);
  }

  back(): void {
    this.index.update((i) => Math.max(0, i - 1));
  }

  /** Ends the tour (skip or completion) and remembers it was seen. */
  finish(): void {
    if (this.activeTourId) {
      this.markSeen(this.activeTourId);
    }
    this.steps.set(null);
    this.index.set(0);
    this.activeTourId = null;
  }
}
