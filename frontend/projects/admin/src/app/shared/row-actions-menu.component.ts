import {
  AfterViewChecked,
  Component,
  ElementRef,
  EventEmitter,
  HostListener,
  Input,
  OnDestroy,
  Output,
  ViewChild,
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
        #trigger
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
        <!-- Rendered as a FIXED-position panel (coordinates computed from the
             trigger) so it escapes the table's overflow/scroll clipping and the
             sibling-row paint order — otherwise the menu is clipped or painted
             under the next table row. -->
        <div
          #menu
          class="shifa-rowmenu__menu"
          role="menu"
          [style.top.px]="menuTop()"
          [style.left.px]="menuLeft()"
        >
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
        position: fixed;
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
export class RowActionsMenuComponent implements AfterViewChecked, OnDestroy {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The actions to list in the menu. An empty list disables the trigger. */
  @Input() actions: RowAction[] = [];

  /** Accessible label for the kebab trigger. */
  @Input() ariaLabel = 'Row actions';

  /** Emits the chosen action's {@link RowAction.key}. */
  @Output() select = new EventEmitter<string>();

  @ViewChild('trigger') private triggerRef?: ElementRef<HTMLButtonElement>;
  @ViewChild('menu') private menuRef?: ElementRef<HTMLElement>;

  /**
   * The panel element after it has been teleported to {@code document.body}
   * (see {@link #teleportMenu}). Tracked so we can detect outside clicks against
   * it and clean it up on close/destroy — once moved to body it is no longer a
   * descendant of the component host.
   */
  private teleportedMenu?: HTMLElement;

  protected readonly open = signal(false);
  /** Fixed-position coordinates for the open menu panel (computed from the trigger). */
  protected readonly menuTop = signal(0);
  protected readonly menuLeft = signal(0);

  /** Approx. menu width used to right-align it under the trigger before it renders. */
  private static readonly MENU_WIDTH = 190;

  protected toggle(event: Event): void {
    event.stopPropagation();
    if (this.open()) {
      this.close();
      return;
    }
    this.positionMenu();
    this.open.set(true);
  }

  /**
   * Moves the just-rendered panel to {@code document.body} so it escapes any
   * ancestor clipping context — the app-wide {@code main.page-body { overflow-x:
   * clip }} guard (added for the no-horizontal-scroll requirement) otherwise
   * clips this fixed-position panel out of view near the right edge. The panel
   * is still fixed-positioned from the trigger's rect, so body is the correct,
   * clip-free parent. Idempotent per open.
   */
  ngAfterViewChecked(): void {
    const el = this.menuRef?.nativeElement;
    if (this.open() && el && el.parentElement !== document.body) {
      document.body.appendChild(el);
      this.teleportedMenu = el;
    }
  }

  /** Closes the menu and removes the teleported panel from body. */
  private close(): void {
    this.open.set(false);
    this.removeTeleportedMenu();
  }

  private removeTeleportedMenu(): void {
    if (this.teleportedMenu && this.teleportedMenu.parentElement === document.body) {
      document.body.removeChild(this.teleportedMenu);
    }
    this.teleportedMenu = undefined;
  }

  ngOnDestroy(): void {
    this.removeTeleportedMenu();
  }

  /** Computes the fixed-position coordinates so the panel sits just under the trigger, right-aligned. */
  private positionMenu(): void {
    const btn = this.triggerRef?.nativeElement;
    if (!btn) {
      return;
    }
    const r = btn.getBoundingClientRect();
    const width = RowActionsMenuComponent.MENU_WIDTH;
    // Right-align the panel's right edge with the trigger's right edge, clamped
    // so it never runs off the left of the viewport.
    const left = Math.max(8, r.right - width);
    this.menuLeft.set(left);
    this.menuTop.set(r.bottom + 4);
  }

  protected choose(event: Event, action: RowAction): void {
    event.stopPropagation();
    if (action.disabled) {
      return;
    }
    this.close();
    this.select.emit(action.key);
  }

  @HostListener('document:click', ['$event'])
  protected onDocumentClick(event: MouseEvent): void {
    if (!this.open()) {
      return;
    }
    const target = event.target as Node;
    // The panel is teleported to body, so an "inside" click is either on the
    // trigger (host) or within the teleported panel itself.
    const insideHost = this.host.nativeElement.contains(target);
    const insideMenu = !!this.teleportedMenu && this.teleportedMenu.contains(target);
    if (!insideHost && !insideMenu) {
      this.close();
    }
  }

  @HostListener('document:keydown.escape')
  protected onEscape(): void {
    if (this.open()) {
      this.close();
    }
  }

  // The menu is fixed-positioned from the trigger's rect, so it won't follow a
  // scrolling table/page — close it on scroll or resize (standard dropdown UX).
  @HostListener('window:scroll')
  @HostListener('window:resize')
  protected onViewportChange(): void {
    if (this.open()) {
      this.close();
    }
  }
}
