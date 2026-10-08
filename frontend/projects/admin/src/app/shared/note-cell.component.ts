import { Component, ElementRef, HostListener, inject, input, signal } from '@angular/core';

/**
 * A compact, professional "order note" cell for dense tables.
 *
 * <p>Instead of letting a free-text note squeeze a narrow table column (which
 * makes long notes wrap character-by-character into an unreadable vertical
 * strip), this renders a single tidy **note chip**: an icon + a one-line
 * preview. Clicking it opens a floating popover with the FULL note — wrapped,
 * scrollable, and dismissible (click-away / Esc). When there is no note it shows
 * a muted dash.
 *
 * <p>Used across the packing queue and the approval queue so the note UX is
 * identical everywhere.
 */
@Component({
  selector: 'admin-note-cell',
  standalone: true,
  template: `
    @if (note()) {
      <div class="note-cell">
        <button
          type="button"
          class="note-chip"
          (click)="toggle($event)"
          [attr.aria-expanded]="open()"
          [title]="open() ? '' : note()!"
        >
          <i class="ti ti-note note-chip__icon"></i>
          <span class="note-chip__preview">{{ note() }}</span>
        </button>

        @if (open()) {
          <div class="note-pop" role="dialog" aria-label="Order note" (click)="$event.stopPropagation()">
            <div class="note-pop__head">
              <i class="ti ti-note me-1"></i><span>Order note</span>
              <button type="button" class="note-pop__close" (click)="close($event)" aria-label="Close">
                <i class="ti ti-x"></i>
              </button>
            </div>
            <div class="note-pop__body">{{ note() }}</div>
          </div>
        }
      </div>
    } @else {
      <span class="text-secondary">—</span>
    }
  `,
  styles: [
    `
      :host {
        display: block;
      }
      .note-cell {
        position: relative;
      }
      /* The in-cell chip: icon + single-line ellipsis preview, amber-tinted so a
         note stands out but reads as one clean line (never wraps). */
      .note-chip {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        max-width: 100%;
        border: 1px solid rgba(184, 134, 11, 0.28);
        background: rgba(255, 193, 7, 0.12);
        color: #7a5a00;
        border-radius: 999px;
        padding: 0.2rem 0.6rem 0.2rem 0.5rem;
        font-size: 0.78rem;
        line-height: 1.2;
        cursor: pointer;
        transition:
          background 0.15s ease,
          border-color 0.15s ease;
      }
      .note-chip:hover {
        background: rgba(255, 193, 7, 0.22);
        border-color: rgba(184, 134, 11, 0.45);
      }
      .note-chip__icon {
        flex: none;
        color: #b8860b;
        font-size: 0.95rem;
      }
      .note-chip__preview {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        max-width: 11rem;
      }
      /* The floating full-note popover — fixed-ish width, readable, scrollable. */
      .note-pop {
        position: absolute;
        z-index: 50;
        top: calc(100% + 6px);
        left: 0;
        width: 20rem;
        max-width: 80vw;
        background: #fff;
        border: 1px solid rgba(15, 51, 36, 0.12);
        border-radius: 10px;
        box-shadow: 0 10px 30px rgba(15, 51, 36, 0.18);
        overflow: hidden;
      }
      .note-pop__head {
        display: flex;
        align-items: center;
        gap: 0.25rem;
        padding: 0.5rem 0.7rem;
        background: rgba(255, 193, 7, 0.14);
        border-bottom: 1px solid rgba(184, 134, 11, 0.2);
        font-weight: 600;
        font-size: 0.8rem;
        color: #7a5a00;
      }
      .note-pop__close {
        margin-left: auto;
        border: 0;
        background: transparent;
        color: #7a5a00;
        cursor: pointer;
        line-height: 1;
        padding: 0.1rem;
        border-radius: 6px;
      }
      .note-pop__close:hover {
        background: rgba(184, 134, 11, 0.15);
      }
      .note-pop__body {
        padding: 0.7rem 0.8rem;
        white-space: pre-wrap;
        word-break: break-word;
        font-size: 0.85rem;
        line-height: 1.5;
        color: #1a2b22;
        max-height: 16rem;
        overflow-y: auto;
      }
    `,
  ],
})
export class NoteCellComponent {
  private readonly host = inject(ElementRef<HTMLElement>);

  /** The note text to show (null/empty renders a muted dash). */
  readonly note = input<string | null | undefined>(null);

  protected readonly open = signal(false);

  toggle(event: Event): void {
    event.stopPropagation();
    this.open.update((v) => !v);
  }

  close(event: Event): void {
    event.stopPropagation();
    this.open.set(false);
  }

  /** Close the popover when clicking anywhere outside this cell. */
  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(event.target as Node)) {
      this.open.set(false);
    }
  }

  /** Close on Escape. */
  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open()) {
      this.open.set(false);
    }
  }
}
