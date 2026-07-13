import {
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  Output,
  inject,
  signal,
} from '@angular/core';
import { NgClass } from '@angular/common';

/**
 * A single per-row action shown inside the kebab (⋮) menu.
 *
 * @property key      stable identifier emitted on {@link RowActionsMenuComponent.select}
 * @property label    the visible menu label
 * @property icon     optional Tabler icon class (e.g. `ti-check`)
 * @property variant  colour intent: default / danger / success / primary
 * @property disabled greys the item out and blocks selection
 */
export interface RowAction {
  key: string;
  label: string;
  icon?: string;
  variant?: 'default' | 'danger' | 'success' | 'primary';
  disabled?: boolean;
}

/**
 * Reusable per-row overflow menu (kebab / ⋮) used across the admin data lists.
 *
 * <p>Replaces the cluster of per-row buttons with a single overflow trigger so
 * rows stay clickable (the parent wires the row click to open the detail view)
 * and the row's actions live behind the menu. Emits {@link select} with the
 * chosen action's {@code key}; the parent maps that to its handler for the row.
 *
 * <p>Closes on outside click, on Escape, and after a selection. The trigger and
 * items call {@code stopPropagation} so tapping the menu never triggers the
 * surrounding row-click.
 */
@Component({
  selector: 'admin-row-actions',
  standalone: true,
  imports: [NgClass],
  template: `
    <div class="shifa-rowmenu" [class.is-open]="open()">
      <button
        type="button"
        class="btn btn-icon btn-ghost-secondary shifa-rowmenu__trigger"
        [attr.aria-expanded]="open()"
        aria-haspopup="menu"
        [attr.aria-label]="ariaLabel"
        [disabled]="!actions.length"
        (click)="toggle($event)"
      >
        <i class="ti ti-dots-vertical" aria-hidden="true"></i>
      </button>
      @if (open()) {
        <div class="shifa-rowmenu__menu" role="menu">
          @for (a of actions; track a.key) {
            <button
              type="button"
              role="menuitem"
              class="shifa-rowmenu__item"
              [ngClass]="'is-' + (a.variant || 'default')"
              [disabled]="a.disabled"
              (click)="choose($event, a)"
            >
              @if (a.icon) {
                <i class="ti {{ a.icon }}" aria-hidden="true"></i>
              }
              <span>{{ a.label }}</span>
            </button>
          }
        </div>
      }
    </div>
  `,
  styles: [
    `
      .shifa-rowmenu {
        position: relative;
        display: inline-flex;
      }
      .shifa-rowmenu__trigger {
        width: 40px;
        height: 40px;
      }
      .shifa-rowmenu__menu {
        position: absolute;
        top: calc(100% + 0.25rem);
        right: 0;
        z-index: 1090;
        min-width: 190px;
        padding: 0.35rem;
        background: #fff;
        border: 1px solid var(--tblr-border-color, #e6e7e9);
        border-radius: 0.6rem;
        box-shadow: 0 12px 32px rgba(15, 34, 58, 0.18);
        animation: shifaRowMenuPop 0.14s ease both;
      }
      @keyframes shifaRowMenuPop {
        from {
          opacity: 0;
          transform: translateY(-4px);
        }
        to {
          opacity: 1;
          transform: translateY(0);
        }
      }
      .shifa-rowmenu__item {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        width: 100%;
        min-height: 42px;
        padding: 0.5rem 0.7rem;
        border: 0;
        background: transparent;
        border-radius: 0.4rem;
        text-align: left;
        font-size: 0.92rem;
        color: #2b3a33;
        cursor: pointer;
      }
      .shifa-rowmenu__item i {
        font-size: 1.15rem;
        flex: 0 0 auto;
        opacity: 0.9;
      }
      .shifa-rowmenu__item:hover:not(:disabled),
      .shifa-rowmenu__item:focus-visible:not(:disabled) {
        background: var(--shifa-green-050, #f2f9f5);
      }
      .shifa-rowmenu__item:disabled {
        opacity: 0.5;
        cursor: not-allowed;
      }
      .shifa-rowmenu__item.is-danger {
        color: var(--tblr-danger, #d63939);
      }
      .shifa-rowmenu__item.is-success {
        color: var(--shifa-green-700, #164632);
      }
      .shifa-rowmenu__item.is-primary {
        color: var(--shifa-green-700, #164632);
      }
      .shifa-rowmenu__item.is-danger:hover:not(:disabled) {
        background: #fdecec;
      }
    `,
  ],
})
export class RowActionsMenuComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The actions to list in the menu. An empty list disables the trigger. */
  @Input() actions: RowAction[] = [];

  /** Accessible label for the kebab trigger. */
  @Input() ariaLabel = 'Row actions';

  /** Emits the chosen action's {@link RowAction.key}. */
  @Output() select = new EventEmitter<string>();

  protected readonly open = signal(false);

  protected toggle(event: Event): void {
    event.stopPropagation();
    this.open.update((v) => !v);
  }

  protected choose(event: Event, action: RowAction): void {
    event.stopPropagation();
    if (action.disabled) {
      return;
    }
    this.open.set(false);
    this.select.emit(action.key);
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.open()) {
      this.open.set(false);
    }
  }
}
