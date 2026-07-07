import { Injectable, effect, signal } from '@angular/core';

/** The two table density modes. */
export type TableDensity = 'comfortable' | 'compact';

const STORAGE_KEY = 'shifa.admin.tableDensity';
const BODY_CLASS = 'density-compact';

/**
 * Small signal-based store for the admin's table density preference
 * (comfortable vs compact). The choice is persisted in {@code localStorage} and
 * mirrored onto a {@code body.density-compact} class so data tables across the
 * app tighten up via a single global CSS rule (see the density section in the
 * global stylesheet). Injected wherever the {@link DensityToggleComponent} or a
 * table lives.
 */
@Injectable({ providedIn: 'root' })
export class TableDensityService {
  /** The current density; defaults to comfortable, restored from storage. */
  readonly density = signal<TableDensity>(this.read());

  /** True when the compact layout is active. */
  readonly compact = signal(this.read() === 'compact');

  constructor() {
    // Persist + reflect onto <body> whenever the preference changes.
    effect(() => {
      const value = this.density();
      this.compact.set(value === 'compact');
      this.persist(value);
      this.applyToBody(value);
    });
    // Apply the restored value immediately on first construction.
    this.applyToBody(this.density());
  }

  /** Flip between comfortable and compact. */
  toggle(): void {
    this.density.update((d) => (d === 'compact' ? 'comfortable' : 'compact'));
  }

  /** Explicitly set the density. */
  set(value: TableDensity): void {
    this.density.set(value);
  }

  private read(): TableDensity {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'compact' ? 'compact' : 'comfortable';
    } catch {
      return 'comfortable';
    }
  }

  private persist(value: TableDensity): void {
    try {
      localStorage.setItem(STORAGE_KEY, value);
    } catch {
      /* storage may be unavailable (private mode) — the class still applies */
    }
  }

  private applyToBody(value: TableDensity): void {
    if (typeof document === 'undefined') {
      return;
    }
    document.body.classList.toggle(BODY_CLASS, value === 'compact');
  }
}
