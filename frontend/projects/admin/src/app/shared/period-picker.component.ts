import { Component, computed, effect, input, output, signal } from '@angular/core';
import { monthStartIst, todayIst } from './business-date.util';
import {
  PERIOD_OPTIONS,
  PeriodChoice,
  PeriodSelection,
  customRangeProblem,
} from './period-selection';

/**
 * Period chooser: Today, Yesterday, Last 7 days, This month, Last month, Overall and Custom.
 *
 * <p>It only reports the choice; the screen that owns it reloads its data. A named period is
 * reported straight away. Choosing Custom shows two date inputs (pre-filled with the current month
 * so the data always matches what is shown) and reports again each time both days form a valid
 * range, so there is no separate Apply step to forget.
 */
@Component({
  selector: 'admin-period-picker',
  template: `
    <div class="pp" role="group" [attr.aria-label]="ariaLabel()">
      <div class="pp__options">
        @for (o of options; track o.key) {
          <button
            type="button"
            class="pp__option"
            [class.is-active]="value().period === o.key"
            [attr.aria-pressed]="value().period === o.key"
            [disabled]="disabled()"
            (click)="select(o.key)"
          >
            {{ o.label }}
          </button>
        }
      </div>

      @if (value().period === 'CUSTOM') {
        <div class="pp__custom">
          <label class="pp__field">
            <span>From</span>
            <input
              type="date"
              class="form-control form-control-sm"
              [value]="from()"
              [max]="today"
              [disabled]="disabled()"
              (change)="setFrom($any($event.target).value)"
              aria-label="Custom range start date"
            />
          </label>
          <label class="pp__field">
            <span>To</span>
            <input
              type="date"
              class="form-control form-control-sm"
              [value]="to()"
              [max]="today"
              [disabled]="disabled()"
              (change)="setTo($any($event.target).value)"
              aria-label="Custom range end date"
            />
          </label>
          @if (problem(); as p) {
            <span class="pp__problem" role="alert">{{ p }}</span>
          }
        </div>
      }
    </div>
  `,
  styles: `
    .pp {
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      min-width: 0;
    }
    .pp__options {
      display: flex;
      gap: 0.35rem;
      overflow-x: auto;
      padding-bottom: 0.15rem;
      scrollbar-width: thin;
    }
    .pp__option {
      flex: 0 0 auto;
      min-height: 2.25rem;
      padding: 0.25rem 0.85rem;
      border: 1px solid var(--tblr-border-color, #dde3df);
      border-radius: 999px;
      background: #fff;
      color: #3b4a43;
      font-size: 0.85rem;
      font-weight: 600;
      white-space: nowrap;
      cursor: pointer;
      transition: background-color 0.12s ease, border-color 0.12s ease, color 0.12s ease;
    }
    .pp__option:hover:not(:disabled) {
      border-color: var(--shifa-green-600, #2a7a54);
    }
    .pp__option.is-active {
      background: var(--shifa-green-700, #1f5d3f);
      border-color: var(--shifa-green-700, #1f5d3f);
      color: #fff;
    }
    .pp__option:disabled {
      opacity: 0.6;
      cursor: not-allowed;
    }
    .pp__custom {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-end;
      gap: 0.5rem;
    }
    .pp__field {
      display: flex;
      flex-direction: column;
      gap: 0.15rem;
      font-size: 0.75rem;
      color: #667085;
      font-weight: 600;
    }
    .pp__field input {
      min-width: 9.5rem;
    }
    .pp__problem {
      font-size: 0.8rem;
      color: #c0392b;
      align-self: center;
    }
  `,
})
export class PeriodPickerComponent {
  /** The current selection (the owning screen keeps it). */
  readonly value = input.required<PeriodSelection>();
  /** Greys the control out while the owning screen is loading. */
  readonly disabled = input(false);
  readonly ariaLabel = input('Period');
  /** Emits the new selection: immediately for a named period, once valid for a custom range. */
  readonly changed = output<PeriodSelection>();

  protected readonly options = PERIOD_OPTIONS;
  /** The latest IST day: later days have no orders, so they are not offered. */
  protected readonly today = todayIst();

  protected readonly from = signal('');
  protected readonly to = signal('');

  /** What is wrong with the typed custom range, shown only while Custom is active. */
  protected readonly problem = computed(() =>
    this.value().period === 'CUSTOM' ? customRangeProblem(this.from(), this.to()) : null,
  );

  constructor() {
    // Keep the date boxes in step with the owner's selection (for example when it resets).
    effect(() => {
      const v = this.value();
      if (v.period === 'CUSTOM') {
        this.from.set(v.from);
        this.to.set(v.to);
      }
    });
  }

  select(key: PeriodChoice): void {
    if (key === 'CUSTOM') {
      // Start from the month so far: a visible range that is already applied.
      const from = this.from() || monthStartIst();
      const to = this.to() || todayIst();
      this.from.set(from);
      this.to.set(to);
      this.changed.emit({ period: 'CUSTOM', from, to });
      return;
    }
    this.changed.emit({ period: key, from: '', to: '' });
  }

  protected setFrom(value: string): void {
    this.from.set(value);
    this.emitCustomIfValid();
  }

  protected setTo(value: string): void {
    this.to.set(value);
    this.emitCustomIfValid();
  }

  private emitCustomIfValid(): void {
    if (customRangeProblem(this.from(), this.to()) === null) {
      this.changed.emit({ period: 'CUSTOM', from: this.from(), to: this.to() });
    }
  }
}
