import { AfterViewInit, Directive, ElementRef, OnDestroy, inject, input, output } from '@angular/core';

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), ' +
  'select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/** Number of dialogs currently holding the body scroll lock (they can nest). */
let scrollLocks = 0;
let previousBodyOverflow = '';

function lockBodyScroll(): void {
  if (scrollLocks === 0) {
    previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
  }
  scrollLocks += 1;
}

function unlockBodyScroll(): void {
  scrollLocks = Math.max(0, scrollLocks - 1);
  if (scrollLocks === 0) {
    document.body.style.overflow = previousBodyOverflow;
  }
}

/**
 * Turns an element into an accessible modal dialog or drawer. Put it on the
 * panel itself (not the backdrop) and render the panel inside an {@code @if}, so
 * the directive lives exactly as long as the dialog is open:
 *
 * <pre>
 *   &#64;if (open()) {
 *     &lt;div class="shifa-overlay" (click)="close()"&gt;&lt;/div&gt;
 *     &lt;aside class="shifa-drawer" adminDialog aria-label="Order details" (dialogClose)="close()"&gt;…&lt;/aside&gt;
 *   }
 * </pre>
 *
 * It provides what the hand-rolled drawers and modals were missing:
 * <ul>
 *   <li>{@code role="dialog"} and {@code aria-modal="true"} (give the element an
 *       {@code aria-label} or {@code aria-labelledby});</li>
 *   <li>focus moves into the dialog on open (the first element marked
 *       {@code data-autofocus}, otherwise the first focusable one, otherwise the
 *       panel) and returns to the opener on close;</li>
 *   <li>Tab / Shift+Tab stay inside the dialog;</li>
 *   <li>Escape emits {@code dialogClose} (only the innermost dialog reacts);</li>
 *   <li>the page behind does not scroll while it is open.</li>
 * </ul>
 * The directive never closes anything itself: the host owns the open state and
 * reacts to {@code dialogClose}.
 */
@Directive({
  selector: '[adminDialog]',
  standalone: true,
  host: {
    role: 'dialog',
    'aria-modal': 'true',
    tabindex: '-1',
    '(keydown)': 'onKeydown($event)',
  },
})
export class DialogDirective implements AfterViewInit, OnDestroy {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** Set to {@code false} for a dialog that must not be dismissed with Escape. */
  readonly closeOnEscape = input(true);

  /** Emitted when the user presses Escape. */
  readonly dialogClose = output<void>();

  /** The element that had focus when the dialog was created (the opener). */
  private readonly opener = document.activeElement as HTMLElement | null;
  private locked = false;

  ngAfterViewInit(): void {
    lockBodyScroll();
    this.locked = true;
    const root = this.host.nativeElement;
    const target = root.querySelector<HTMLElement>('[data-autofocus]') ?? this.focusable()[0] ?? root;
    target.focus({ preventScroll: true });
  }

  ngOnDestroy(): void {
    if (this.locked) {
      unlockBodyScroll();
      this.locked = false;
    }
    const opener = this.opener;
    if (opener && opener !== document.body && document.contains(opener)) {
      opener.focus?.({ preventScroll: true });
    }
  }

  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'Escape') {
      if (this.closeOnEscape() && !event.defaultPrevented) {
        event.preventDefault();
        // Keep an outer dialog (when one wraps this one) from also closing.
        event.stopPropagation();
        this.dialogClose.emit();
      }
      return;
    }
    if (event.key !== 'Tab') {
      return;
    }
    const items = this.focusable();
    if (items.length === 0) {
      event.preventDefault();
      this.host.nativeElement.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement as HTMLElement | null;
    const root = this.host.nativeElement;
    if (event.shiftKey && (active === first || active === root)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }

  private focusable(): HTMLElement[] {
    return Array.from(this.host.nativeElement.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(
      (el) => el.getClientRects().length > 0,
    );
  }
}
