import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  Output,
  computed,
  inject,
  signal,
} from '@angular/core';
import { Product } from 'core';
import { fuzzyScore } from './state-typeahead.component';

/**
 * A lightweight product search / autocomplete for order entry, offered ALONGSIDE
 * the plain product dropdown. As the salesperson types, it fuzzy-matches the
 * published catalog by product name or SKU (reusing {@link fuzzyScore}) and shows
 * a ranked list with keyboard navigation; picking one emits the chosen
 * {@link Product}. The parent keeps the line's {@code productId} form control as
 * the single source of truth, so the dropdown and this field stay in sync.
 */
@Component({
  selector: 'admin-product-typeahead',
  standalone: true,
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
        [value]="value()"
        [placeholder]="placeholder"
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
              <span class="prod-ta__meta">₹{{ m.salePrice }} · {{ m.sku }}</span>
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
        justify-content: space-between;
        align-items: baseline;
        gap: 0.75rem;
        padding: 0.55rem 0.85rem;
        cursor: pointer;
        font-size: 0.95rem;
        color: #2b3a32;
      }
      .prod-ta__opt.is-active,
      .prod-ta__opt:hover {
        background: var(--shifa-green-050, #f2f9f5);
        color: var(--shifa-green-700, #164632);
      }
      .prod-ta__meta {
        font-size: 0.8rem;
        color: #6b7a71;
        white-space: nowrap;
      }
    `,
  ],
})
export class ProductTypeaheadComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The selectable (published) products, supplied by the parent. */
  @Input() set products(value: Product[] | null | undefined) {
    this.productList.set(value ?? []);
    this.syncDisplay();
  }

  /** The currently-selected product id (kept in the parent's form control). */
  @Input() set selectedId(value: number | null | undefined) {
    this.selected.set(value ?? null);
    this.syncDisplay();
  }

  @Input() placeholder = 'Search product by name or SKU…';

  /** Marks the field invalid (parent decides from the form-control state). */
  @Input() invalid = false;

  /** Emits the chosen product when the user picks a match. */
  @Output() readonly picked = new EventEmitter<Product>();

  protected readonly value = signal('');
  protected readonly open = signal(false);
  protected readonly active = signal(0);
  private readonly productList = signal<Product[]>([]);
  private readonly selected = signal<number | null>(null);
  /** True while the user is actively typing, so we don't overwrite their text. */
  private typing = false;

  /** The fuzzy-ranked matches for the current query (top 8), by name or SKU. */
  protected readonly matches = computed(() => {
    const query = this.value().trim();
    const scored: { product: Product; score: number }[] = [];
    for (const product of this.productList()) {
      const nameScore = fuzzyScore(product.name, query);
      const skuScore = product.sku ? fuzzyScore(product.sku, query) : null;
      const score = bestScore(nameScore, skuScore);
      if (score !== null) {
        scored.push({ product, score });
      }
    }
    scored.sort((a, b) => a.score - b.score || a.product.name.localeCompare(b.product.name));
    return scored.slice(0, 8).map((s) => s.product);
  });

  /** Reflects the selected product's name into the input (unless the user is typing). */
  private syncDisplay(): void {
    if (this.typing) {
      return;
    }
    const id = this.selected();
    const product = this.productList().find((p) => p.id === id);
    this.value.set(product ? product.name : '');
  }

  protected onInput(event: Event): void {
    this.typing = true;
    this.value.set((event.target as HTMLInputElement).value);
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
    this.typing = false;
    this.value.set(product.name);
    this.selected.set(product.id);
    this.open.set(false);
    this.picked.emit(product);
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.host.nativeElement.contains(event.target)) {
      this.open.set(false);
      // Leaving the field without a pick: restore the selected product's name.
      this.typing = false;
      this.syncDisplay();
    }
  }
}

/** The better (lower) of two nullable fuzzy scores, or null when both miss. */
function bestScore(a: number | null, b: number | null): number | null {
  if (a === null) {
    return b;
  }
  if (b === null) {
    return a;
  }
  return Math.min(a, b);
}
