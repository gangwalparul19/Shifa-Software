import { Component, HostListener, Input, signal } from '@angular/core';

/**
 * A small, dismissible "?" contextual-help tooltip (enhancement: "First-run
 * guided tour + contextual help tooltips"). Drop it next to any label or
 * heading with a short explanation for less-experienced staff:
 *
 * <pre>Order status &lt;admin-help-tip text="..."/&gt;</pre>
 *
 * <p>Click/tap toggles the tip open (works on touch, unlike hover-only), and
 * clicking outside closes it. Kept intentionally tiny and framework-free
 * (no floating-ui dependency) since it never needs viewport-aware repositioning
 * for a short one-line tip.
 */
@Component({
  selector: 'admin-help-tip',
  standalone: true,
  template: `
    <span class="ht-wrap">
      <button
        type="button"
        class="ht-btn"
        (click)="toggle($event)"
        [attr.aria-label]="'Help: ' + text"
        [attr.aria-expanded]="open()"
      >
        <i class="ti ti-help-circle" aria-hidden="true"></i>
      </button>
      @if (open()) {
        <span class="ht-bubble" role="tooltip">{{ text }}</span>
      }
    </span>
  `,
  styles: [
    `
      .ht-wrap {
        position: relative;
        display: inline-flex;
        vertical-align: middle;
        margin-left: 0.3rem;
      }
      .ht-btn {
        border: none;
        background: none;
        color: #8a938e;
        padding: 0;
        line-height: 1;
        cursor: pointer;
        display: inline-flex;
        font-size: 1rem;
      }
      .ht-btn:hover {
        color: var(--shifa-green-500, #1f5d3f);
      }
      .ht-bubble {
        position: absolute;
        z-index: 40;
        top: 130%;
        left: 0;
        width: 220px;
        background: #1c2b24;
        color: #fff;
        font-size: 0.78rem;
        line-height: 1.35;
        padding: 0.5rem 0.65rem;
        border-radius: 8px;
        box-shadow: 0 8px 20px rgba(15, 23, 20, 0.25);
      }
    `,
  ],
})
export class HelpTipComponent {
  @Input({ required: true }) text = '';

  protected readonly open = signal(false);

  toggle(event: MouseEvent): void {
    event.stopPropagation();
    this.open.update((v) => !v);
  }

  @HostListener('document:click')
  onDocumentClick(): void {
    this.open.set(false);
  }
}
