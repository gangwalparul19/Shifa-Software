import {
  Component,
  ElementRef,
  HostListener,
  effect,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { TranslateService, TranslatePipe } from '@ngx-translate/core';
import { CartService } from '../cart.service';
import { WhatsAppService } from '../whatsapp.service';
import { TrackService, OrderTracking } from '../../track/track.service';
import { formatInr } from '../money';

/** A single chat bubble in the assistant conversation. */
interface ChatMessage {
  id: number;
  from: 'bot' | 'user';
  /** Body text; may contain newlines (rendered with `white-space: pre-line`). */
  text: string;
  /** Optional call-to-action link rendered under the bubble (e.g. courier tracking). */
  link?: { url: string; label: string; external: boolean };
}

/**
 * Floating engagement dock (Phase F): a bottom-right stack with a WhatsApp
 * quick-action and a self-contained order-status chat assistant.
 *
 * <p><b>WhatsApp</b> — opens a prefilled {@code wa.me} deep link built from the
 * current cart (or a generic enquiry when empty) via {@link WhatsAppService}.
 *
 * <p><b>Chat assistant</b> — a lightweight bot (no backend chat) that lets a
 * customer check an order: they paste an order code (or tap "Track my order")
 * and the widget calls the public {@code GET /api/track/{orderCode}} through
 * {@link TrackService}, then replies with the status, courier + AWB + tracking
 * link, estimated delivery, COD amount and payment status when present. A
 * not-found lookup is handled gracefully. The panel is a focus-managed dialog:
 * it traps initial focus on the input, closes on {@code ESC}, and restores
 * focus to the toggle button. All copy is translated via ngx-translate.
 */
@Component({
  selector: 'sf-engagement-dock',
  imports: [TranslatePipe],
  templateUrl: './engagement-dock.component.html',
  styleUrl: './engagement-dock.component.css',
})
export class EngagementDockComponent {
  private readonly cart = inject(CartService);
  private readonly whatsapp = inject(WhatsAppService);
  private readonly track = inject(TrackService);
  private readonly translate = inject(TranslateService);

  private readonly input = viewChild<ElementRef<HTMLInputElement>>('chatInput');
  private readonly toggleBtn = viewChild<ElementRef<HTMLButtonElement>>('chatToggle');

  protected readonly open = signal(false);
  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly busy = signal(false);
  protected readonly draft = signal('');

  private nextId = 1;

  constructor() {
    // When the panel opens, seed the greeting once and move focus to the input.
    effect(() => {
      if (this.open()) {
        if (this.messages().length === 0) {
          this.pushBot(this.t('chat.greeting'));
        }
        queueMicrotask(() => this.input()?.nativeElement.focus());
      }
    });
  }

  // --- WhatsApp handoff ----------------------------------------------------

  /** wa.me link prefilled with the current cart (or a generic enquiry when empty). */
  whatsappHref(): string {
    return this.whatsapp.cartLink(this.cart.items());
  }

  // --- Chat panel ----------------------------------------------------------

  toggle(): void {
    this.open.update((o) => !o);
  }

  close(): void {
    this.open.set(false);
    queueMicrotask(() => this.toggleBtn()?.nativeElement.focus());
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.open()) {
      this.close();
    }
  }

  /** Quick-reply: prompt the customer for their order code. */
  quickTrack(): void {
    this.pushUser(this.t('chat.quickTrack'));
    this.pushBot(this.t('chat.askForCode'));
    queueMicrotask(() => this.input()?.nativeElement.focus());
  }

  /** Quick-reply: hand off to WhatsApp with a generic enquiry. */
  quickWhatsapp(): void {
    this.pushUser(this.t('chat.quickWhatsapp'));
    window.open(this.whatsapp.genericLink(), '_blank', 'noopener');
  }

  /** Submits the input box: treats the text as an order code and looks it up. */
  submit(): void {
    const code = this.draft().trim();
    if (!code || this.busy()) {
      return;
    }
    this.draft.set('');
    this.pushUser(code);
    this.lookup(code);
  }

  private lookup(code: string): void {
    this.busy.set(true);
    const pending = this.pushBot(this.t('chat.looking'));
    this.track.track(code).subscribe({
      next: (tracking) => {
        this.replace(pending, this.formatTracking(tracking));
        this.pushBot(this.t('chat.anythingElse'));
        this.busy.set(false);
      },
      error: (err) => {
        const key = err?.status === 404 ? 'chat.notFound' : 'chat.error';
        this.replace(pending, { id: this.nextId++, from: 'bot', text: this.t(key) });
        this.busy.set(false);
      },
    });
  }

  /** Turns a tracking response into a friendly, multi-line bot reply. */
  private formatTracking(tracking: OrderTracking): ChatMessage {
    const lines: string[] = [
      this.t('chat.statusLine', {
        code: tracking.orderCode,
        status: humanizeStatus(String(tracking.orderStatus)),
      }),
    ];
    if (tracking.courierName || tracking.awb) {
      lines.push(
        this.t('chat.courierLine', {
          courier: tracking.courierName ?? '—',
          awb: tracking.awb ?? '—',
        }),
      );
    }
    if (tracking.estimatedDelivery) {
      lines.push(this.t('chat.etaLine', { eta: formatEta(tracking.estimatedDelivery) }));
    }
    if (tracking.codAmount != null && tracking.codAmount !== '') {
      lines.push(this.t('chat.codLine', { amount: formatInr(tracking.codAmount) }));
    }
    if (tracking.paymentStatus) {
      lines.push(
        this.t('chat.paymentLine', { status: humanizeStatus(tracking.paymentStatus) }),
      );
    }
    const link = tracking.trackingUrl
      ? { url: tracking.trackingUrl, label: this.t('chat.trackLink'), external: true }
      : undefined;
    return { id: this.nextId++, from: 'bot', text: lines.join('\n'), link };
  }

  // --- message helpers -----------------------------------------------------

  private pushBot(text: string): ChatMessage {
    const msg: ChatMessage = { id: this.nextId++, from: 'bot', text };
    this.messages.update((list) => [...list, msg]);
    return msg;
  }

  private pushUser(text: string): void {
    this.messages.update((list) => [...list, { id: this.nextId++, from: 'user', text }]);
  }

  /** Replaces the transient "looking…" placeholder with the resolved reply. */
  private replace(placeholder: ChatMessage, replacement: ChatMessage): void {
    this.messages.update((list) =>
      list.map((m) => (m.id === placeholder.id ? replacement : m)),
    );
  }

  private t(key: string, params?: Record<string, unknown>): string {
    return this.translate.instant(key, params) as string;
  }
}

/** Humanizes an enum-like status ("OUT_FOR_DELIVERY" → "Out For Delivery"). */
function humanizeStatus(status: string): string {
  return status
    .toLowerCase()
    .split(/[_\s]+/)
    .filter(Boolean)
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

/** Formats an ISO date/datetime for the India locale, falling back to the raw value. */
function formatEta(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });
}
