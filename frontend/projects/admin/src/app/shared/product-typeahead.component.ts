import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  Output,
  computed,
  forwardRef,
  inject,
  signal,
} from '@angular/core';
import { ControlValueAccessor, NG_VALUE_ACCESSOR } from '@angular/forms';
import { Product } from 'core';
import { fuzzyScore } from './state-typeahead.component';

/**
 * A reactive-form-friendly fuzzy typeahead combobox for picking a product on an
 * order line (product-catalog-pricing-gst Req 10.1). Registers as a
 * {@link ControlValueAccessor} so it binds with {@code formControlName}; the
 * value is the selected product's numeric id (or {@code null}).
 *
 * <p>Type to fuzzy-search by product name OR SKU, or focus/click to browse the
 * full list like a dropdown — so it replaces the plain {@code <select>} while
 * keeping the browse-all behaviour. Each option shows the name, price and pack
 * size. Emits {@link selected} when a product is chosen so the parent can
 * pre-fill the line rate.
 */
@Component({
  selector: 'admin-product-typeahead',
  standalone: true,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => ProductTypeaheadComponent),
      multi: true,
    },
  ],
  template: `
    <div class="prod-ta">
      <input
        #input
        type="text"
        class="form-control"
        role="combobox"
        aria-autocomplete="list"
        [attr.aria-expanded]="open()"
        [class.is-invalid]="invalid"
        [value]="query()"
        [placeholder]="placeholder"
        [disabled]="disabled()"
        autocomplete="off"
        (input)="onInput($event)"
        (focus)="onFocus()"
        (keydown)="onKeydown($event)"
      />
      @if (open() && matches().length) {
        <ul class="prod-ta__menu" role="listbox">
          @for (m of matches(); track m.id; let i = $index) {
            <li
              role="option"
              class="prod-ta__opt"
              [class.is-active]="i === active()"
              [attr.aria-selected]="i === active()"
              (mousedown)="select(m)"
              (mouseenter)="active.set(i)"
            >
              <span class="prod-ta__name">{{ m.name }}</span>
              <span class="prod-ta__meta">₹{{ m.salePrice }}@if (m.wtMl) { · {{ m.wtMl }} } · {{ m.sku }}</span>
            </li>
          }
        </ul>
      }
    </div>
  `,
  styles: [
    `
      .prod-ta {
        position: relative;
      }
      .prod-ta__menu {
        position: absolute;
        z-index: 40;
        top: calc(100% + 2px);
        left: 0;
        right: 0;
        margin: 0;
        padding: 0.25rem 0;
        list-style: none;
        max-height: 16rem;
        overflow-y: auto;
        background: #fff;
        border: 1px solid rgba(15, 51, 36, 0.15);
        border-radius: 10px;
        box-shadow: 0 8px 24px rgba(15, 51, 36, 0.12);
      }
      .prod-ta__opt {
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
        padding: 0.5rem 0.85rem;
        cursor: pointer;
        color: #2b3a32;
      }
      .prod-ta__opt.is-active,
      .prod-ta__opt:hover {
        background: var(--shifa-green-050, #f2f9f5);
        color: var(--shifa-green-700, #164632);
      }
      .prod-ta__name {
        font-size: 0.95rem;
        font-weight: 500;
      }
      .prod-ta__meta {
        font-size: 0.8rem;
        color: #6b7b72;
      }
    `,
  ],
})
export class ProductTypeaheadComponent implements ControlValueAccessor {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The selectable products (published catalog, supplied by the parent). */
  @Input() set products(value: Product[] | null | undefined) {
    this.productList.set(value ?? []);
  }

  @Input() placeholder = 'Type to search a product…';

  /** Marks the control invalid (parent decides based on the form control state). */
  @Input() invalid = false;

  /** Emits the chosen product's id when a selection is made. */
  @Output() readonly selected = new EventEmitter<number>();

  /** The current text in the input (search query or selected product name). */
  protected readonly query = signal('');
  protected readonly open = signal(false);
  protected readonly active = signal(0);
  protected readonly disabled = signal(false);
  private readonly productList = signal<Product[]>([]);
  /** The currently selected product id (the control value). */
  private readonly selectedId = signal<number | null>(null);

  /** Fuzzy-ranked matches (by name or SKU) for the current query; browse-all when blank (top 30). */
  protected readonly matches = computed(() => {
    const q = this.query().trim();
    const selectedName = this.nameOf(this.selectedId());
    // When the box just shows the selected product's name (no active typing),
    // treat it as an empty query so focusing browses the whole list.
    const effective = q === selectedName ? '' : q;
    const scored: { product: Product; score: number }[] = [];
    for (const product of this.productList()) {
      const byName = fuzzyScore(product.name, effective);
      const bySku = fuzzyScore(product.sku, effective);
      const score = min2(byName, bySku);
      if (score !== null) {
        scored.push({ product, score });
      }
    }
    scored.sort((a, b) => a.score - b.score || a.product.name.localeCompare(b.product.name));
    return scored.slice(0, 30).map((s) => s.product);
  });

  private nameOf(id: number | null): string {
    if (id === null) {
      return '';
    }
    return this.productList().find((p) => p.id === id)?.name ?? '';
  }

  // --- ControlValueAccessor ------------------------------------------------
  private onChange: (value: number | null) => void = () => {};
  private onTouched: () => void = () => {};

  writeValue(value: number | null): void {
    this.selectedId.set(value ?? null);
    this.query.set(this.nameOf(value ?? null));
  }

  registerOnChange(fn: (value: number | null) => void): void {
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
    this.query.set(text);
    // Typing invalidates any prior selection until one is picked from the list.
    if (this.selectedId() !== null) {
      this.selectedId.set(null);
      this.onChange(null);
    }
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

  protected select(product: Product): void {
    this.selectedId.set(product.id);
    this.query.set(product.name);
    this.onChange(product.id);
    this.open.set(false);
    this.onTouched();
    this.selected.emit(product.id);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target)) {
      if (this.open()) {
        this.onTouched();
        // Restore the selected product's name if the user typed but didn't pick.
        this.query.set(this.nameOf(this.selectedId()));
      }
      this.open.set(false);
    }
  }
}

/** Returns the smaller non-null score, or null when both are null. */
function min2(a: number | null, b: number | null): number | null {
  if (a === null) {
    return b;
  }
  if (b === null) {
    return a;
  }
  return Math.min(a, b);
}
