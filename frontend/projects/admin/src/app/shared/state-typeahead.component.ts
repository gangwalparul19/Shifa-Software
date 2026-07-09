import {
  Component,
  ElementRef,
  HostListener,
  Input,
  computed,
  forwardRef,
  inject,
  signal,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';

/**
 * Scores a candidate against a fuzzy query. Returns {@code null} when the query
 * is not a subsequence of the candidate (no match), otherwise a lower-is-better
 * score. An exact / prefix / word-start hit is ranked ahead of a scattered
 * subsequence, and tighter (less-spread) matches beat looser ones. Matching is
 * case- and diacritic-insensitive and ignores spaces in the query so "tn",
 * "tamilnadu" and "tamil" all find "Tamil Nadu".
 */
export function fuzzyScore(candidate: string, query: string): number | null {
  const hay = candidate.toLowerCase();
  const needle = query.toLowerCase().replace(/\s+/g, '');
  if (needle === '') {
    return 0;
  }
  if (hay.replace(/\s+/g, '').startsWith(needle)) {
    return -1000; // strongest: prefix of the collapsed candidate
  }
  // Word-start acronym match (e.g. "up" -> "Uttar Pradesh").
  const initials = candidate
    .split(/\s+/)
    .map((w) => w[0]?.toLowerCase() ?? '')
    .join('');
  if (initials.startsWith(needle)) {
    return -900;
  }
  // General subsequence walk over the (space-preserving) haystack.
  let hi = 0;
  let firstHit = -1;
  let lastHit = -1;
  for (let ni = 0; ni < needle.length; ni++) {
    const ch = needle[ni];
    let found = -1;
    while (hi < hay.length) {
      if (hay[hi] === ch) {
        found = hi;
        hi++;
        break;
      }
      hi++;
    }
    if (found === -1) {
      return null; // needle char not found in order -> no match
    }
    if (firstHit === -1) {
      firstHit = found;
    }
    lastHit = found;
  }
  const spread = lastHit - firstHit; // tighter clusters rank better
  return spread + firstHit * 0.1;
}

/**
 * A reactive-form-friendly fuzzy typeahead for picking a delivery state.
 *
 * <p>Registers as a {@link ControlValueAccessor} so it can be used with
 * {@code formControlName}. The list of selectable states is supplied via the
 * {@link options} input (loaded from {@code GET /api/states}); the value is the
 * chosen state name. Free typing is preserved so an unlisted value can still be
 * entered, but the dropdown surfaces fuzzy matches (e.g. "mh" -> Maharashtra,
 * "up" -> Uttar Pradesh, "tamilnadu" -> Tamil Nadu) with keyboard navigation.
 */
@Component({
  selector: 'admin-state-typeahead',
  standalone: true,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => StateTypeaheadComponent),
      multi: true,
    },
  ],
  template: `
    <div class="state-ta">
      <input
        #input
        type="text"
        class="form-control"
        role="combobox"
        aria-autocomplete="list"
        [attr.aria-expanded]="open()"
        [class.is-invalid]="invalid"
        [value]="value()"
        [placeholder]="placeholder"
        [disabled]="disabled()"
        autocomplete="off"
        (input)="onInput($event)"
        (focus)="onFocus()"
        (keydown)="onKeydown($event)"
      />
      @if (open() && matches().length) {
        <ul class="state-ta__menu" role="listbox">
          @for (m of matches(); track m; let i = $index) {
            <li
              role="option"
              class="state-ta__opt"
              [class.is-active]="i === active()"
              [attr.aria-selected]="i === active()"
              (mousedown)="select(m)"
              (mouseenter)="active.set(i)"
            >
              {{ m }}
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: [
    `
      .state-ta {
        position: relative;
      }
      .state-ta__menu {
        position: absolute;
        z-index: 40;
        top: calc(100% + 2px);
        left: 0;
        right: 0;
        margin: 0;
        padding: 0.25rem 0;
        list-style: none;
        max-height: 15rem;
        overflow-y: auto;
        background: #fff;
        border: 1px solid rgba(15, 51, 36, 0.15);
        border-radius: 10px;
        box-shadow: 0 8px 24px rgba(15, 51, 36, 0.12);
      }
      .state-ta__opt {
        padding: 0.55rem 0.85rem;
        cursor: pointer;
        font-size: 0.95rem;
        color: #2b3a32;
      }
      .state-ta__opt.is-active,
      .state-ta__opt:hover {
        background: var(--shifa-green-050, #f2f9f5);
        color: var(--shifa-green-700, #164632);
      }
    `,
  ],
})
export class StateTypeaheadComponent implements ControlValueAccessor {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** Selectable state names (loaded from the API by the parent). */
  @Input() set options(value: string[] | null | undefined) {
    this.optionList.set(value ?? []);
  }

  @Input() placeholder = 'Start typing a state…';

  /** Marks the control invalid (parent decides based on the form control state). */
  @Input() invalid = false;

  protected readonly value = signal('');
  protected readonly open = signal(false);
  protected readonly active = signal(0);
  protected readonly disabled = signal(false);
  private readonly optionList = signal<string[]>([]);

  /** The fuzzy-ranked matches for the current query (top 8). */
  protected readonly matches = computed(() => {
    const query = this.value().trim();
    const scored: { name: string; score: number }[] = [];
    for (const name of this.optionList()) {
      const score = fuzzyScore(name, query);
      if (score !== null) {
        scored.push({ name, score });
      }
    }
    scored.sort((a, b) => a.score - b.score || a.name.localeCompare(b.name));
    return scored.slice(0, 8).map((s) => s.name);
  });

  // --- ControlValueAccessor ------------------------------------------------
  private onChange: (value: string) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: string): void {
    this.value.set(value ?? '');
  }

  registerOnChange(fn: (value: string) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.disabled.set(isDisabled);
  }

  // --- Interaction ---------------------------------------------------------

  protected onInput(event: Event): void {
    const text = (event.target as HTMLInputElement).value;
    this.value.set(text);
    this.onChange(text);
    this.open.set(true);
    this.active.set(0);
  }

  protected onFocus(): void {
    this.open.set(true);
    this.active.set(0);
  }

  protected onKeydown(event: KeyboardEvent): void {
    const list = this.matches();
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      this.open.set(true);
      this.active.set(Math.min(this.active() + 1, list.length - 1));
    } else if (event.key === 'ArrowUp') {
      event.preventDefault();
      this.active.set(Math.max(this.active() - 1, 0));
    } else if (event.key === 'Enter') {
      if (this.open() && list.length) {
        event.preventDefault();
        this.select(list[this.active()] ?? list[0]);
      }
    } else if (event.key === 'Escape') {
      this.open.set(false);
    }
  }

  protected select(name: string): void {
    this.value.set(name);
    this.onChange(name);
    this.open.set(false);
    this.onTouched();
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target)) {
      if (this.open()) {
        this.onTouched();
      }
      this.open.set(false);
    }
  }
}
