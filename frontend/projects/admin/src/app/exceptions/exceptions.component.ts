import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IstDatePipe } from '../shared/ist-date.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { ExceptionsService } from './exceptions.service';
import {
  AdminExceptionItem,
  AdminExceptionResponse,
  ExceptionCategory,
  EXCEPTION_CATEGORY_LABELS,
} from './exceptions.model';
import { PageHeaderComponent } from '../shared/page-header.component';
import { StatePanelComponent } from '../shared/state-panel.component';
import { ToastService } from '../shared/toast.service';
import { openWhatsApp, whatsAppMessage } from '../shared/whatsapp.util';
import { OrderStatus } from 'core';
import { humanizeStatus } from '../shared/status-badge.component';

@Component({
  selector: 'admin-exceptions',
  imports: [RouterLink, IstDatePipe, PageHeaderComponent, StatePanelComponent],
  templateUrl: './exceptions.component.html',
  styleUrl: './exceptions.component.css',
})
export class ExceptionsComponent implements OnInit {
  private readonly service = inject(ExceptionsService);
  private readonly toasts = inject(ToastService);

  protected readonly response = signal<AdminExceptionResponse | null>(null);
  protected readonly loading = signal(true);
  protected readonly loadError = signal<string | null>(null);
  protected readonly categories = Object.keys(EXCEPTION_CATEGORY_LABELS) as ExceptionCategory[];
  protected readonly categoryLabels = EXCEPTION_CATEGORY_LABELS;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(null);
    this.service.list().subscribe({
      next: (value) => {
        this.response.set(value);
        this.loading.set(false);
      },
      error: (error: HttpErrorResponse) => {
        this.loadError.set(error.status === 403
          ? 'Only administrators can view the exception center.'
          : 'Could not load exceptions. Please try again.');
        this.loading.set(false);
      },
    });
  }

  items(): AdminExceptionItem[] {
    return this.response()?.items ?? [];
  }

  count(category: ExceptionCategory): number {
    return this.response()?.countsByCategory?.[category] ?? 0;
  }

  categoryIcon(category: string): string {
    switch (category) {
      case 'APPROVAL': return 'ti-checklist';
      case 'PAYMENT': return 'ti-shield-check';
      case 'DELIVERY': return 'ti-truck-off';
      case 'CLAIM': return 'ti-file-alert';
      case 'INSIGHT': return 'ti-bulb';
      default: return 'ti-alert-triangle';
    }
  }

  severityClass(severity: string): string {
    return severity === 'DANGER' || severity === 'HIGH'
      ? 'bg-red-lt text-red'
      : severity === 'WARNING' || severity === 'MEDIUM'
        ? 'bg-yellow-lt text-yellow'
        : 'bg-blue-lt text-blue';
  }

  statusLabel(status: OrderStatus | null | undefined): string {
    return status ? humanizeStatus(status) : '';
  }

  money(value: string | number | null | undefined): string {
    const amount = Number(value ?? 0);
    return Number.isFinite(amount) ? `₹${amount.toLocaleString('en-IN', { maximumFractionDigits: 2 })}` : '₹0';
  }

  actionLabel(item: AdminExceptionItem): string {
    switch (item.category) {
      case 'APPROVAL': return 'Review approval';
      case 'PAYMENT': return 'Review payment';
      case 'DELIVERY': return 'Open order';
      case 'CLAIM': return 'Open reconciliation';
      case 'INSIGHT': return 'Open insight';
      default: return 'Open';
    }
  }

  actionQuery(item: AdminExceptionItem): Record<string, string> | null {
    return item.orderCode ? { q: item.orderCode } : null;
  }

  call(item: AdminExceptionItem, event: Event): void {
    event.stopPropagation();
    if (item.customerMobile) {
      window.location.href = `tel:${item.customerMobile}`;
    }
  }

  message(item: AdminExceptionItem, event: Event): void {
    event.stopPropagation();
    if (!item.customerMobile) {
      this.toasts.error('This exception has no customer mobile number.');
      return;
    }
    if (!openWhatsApp(item.customerMobile, whatsAppMessage('followup', {
      customerName: item.customerName,
      orderCode: item.orderCode,
    }))) {
      this.toasts.error('No valid mobile number to message on WhatsApp.');
    }
  }
}
