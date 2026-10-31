import { Component, ElementRef, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { PageHeaderComponent } from '../shared/page-header.component';
import { AssistantService } from './assistant.service';
import { AssistantAnswer, AssistantTable, ChatMessage } from './assistant.model';

/** Most messages kept on screen; older ones scroll out of memory. */
const MAX_MESSAGES = 100;

/**
 * "Ask Shifa": a chat page where an admin types a business question ("orders
 * today from Shopify", "total sales by Sameer Khan", "units of Gut Cleanser this
 * month") and gets an answer computed from the OMS database. The conversation is
 * kept in memory only and is gone when the page is closed.
 */
@Component({
  selector: 'admin-assistant',
  imports: [PageHeaderComponent],
  templateUrl: './assistant.component.html',
  styleUrl: './assistant.component.css',
})
export class AssistantComponent implements OnInit {
  private readonly service = inject(AssistantService);

  private readonly scroller = viewChild<ElementRef<HTMLElement>>('scroller');
  private readonly input = viewChild<ElementRef<HTMLTextAreaElement>>('box');

  protected readonly messages = signal<ChatMessage[]>([]);
  protected readonly draft = signal('');
  protected readonly sending = signal(false);
  private nextId = 1;

  /** Only the newest bot reply shows its suggestion chips, to keep the thread readable. */
  protected readonly lastBotId = computed(() => {
    const bots = this.messages().filter((m) => m.from === 'bot');
    return bots.length ? bots[bots.length - 1].id : null;
  });

  protected readonly canSend = computed(() => this.draft().trim().length > 0 && !this.sending());

  ngOnInit(): void {
    // The server's help reply doubles as the welcome message, with examples built from real names.
    this.sending.set(true);
    this.service.ask('help').subscribe({
      next: (reply) => {
        this.push(this.fromReply(reply));
        this.sending.set(false);
      },
      error: () => {
        this.push({
          id: this.nextId++,
          from: 'bot',
          text: 'Hi! Ask me about orders, sales or product quantities, for example "orders today from Shopify".',
          suggestions: ['How many orders were placed today?', 'Total sales this month'],
        });
        this.sending.set(false);
      },
    });
  }

  protected onInput(event: Event): void {
    this.draft.set((event.target as HTMLTextAreaElement).value);
  }

  /** Enter sends; Shift+Enter adds a new line. */
  protected onEnter(event: Event): void {
    const key = event as KeyboardEvent;
    if (key.shiftKey) {
      return;
    }
    key.preventDefault();
    this.send();
  }

  protected send(text?: string): void {
    const question = (text ?? this.draft()).trim();
    if (!question || this.sending()) {
      return;
    }
    this.push({ id: this.nextId++, from: 'user', text: question });
    this.draft.set('');
    const box = this.input()?.nativeElement;
    if (box) {
      box.value = '';
    }
    this.sending.set(true);
    this.service.ask(question).subscribe({
      next: (reply) => {
        this.push(this.fromReply(reply));
        this.sending.set(false);
      },
      error: () => {
        this.push({
          id: this.nextId++,
          from: 'bot',
          text: 'Sorry, I could not get an answer just now. Please check your connection and try again.',
          problem: true,
        });
        this.sending.set(false);
      },
    });
  }

  protected clear(): void {
    this.messages.set([]);
    this.ngOnInit();
  }

  /** A column of amounts and counts reads best right-aligned; text columns stay left. */
  protected isNumericColumn(table: AssistantTable, index: number): boolean {
    if (index === 0 || table.rows.length === 0) {
      return false;
    }
    return table.rows.every((row) => /^(?:[-+]?(?:₹-?)?[\d,]+(?:\.\d+)?%?|-|n\/a)$/.test((row[index] ?? '').trim()));
  }

  /** Total / summary rows are emphasised in tables. */
  protected isTotalRow(row: string[]): boolean {
    // A null / empty first cell must not throw while the table renders.
    const first = row[0] ?? '';
    return first.startsWith('Total') || first === 'All sources';
  }

  private fromReply(reply: AssistantAnswer): ChatMessage {
    return {
      id: this.nextId++,
      from: 'bot',
      text: reply.answer,
      table: reply.table,
      suggestions: reply.suggestions,
      problem: !reply.understood,
    };
  }

  private push(message: ChatMessage): void {
    // Keep the conversation bounded: each answer can carry a table, and the page may stay open all day.
    this.messages.update((list) => [...list, message].slice(-MAX_MESSAGES));
    setTimeout(() => {
      const el = this.scroller()?.nativeElement;
      if (el) {
        el.scrollTop = el.scrollHeight;
      }
    });
  }
}
