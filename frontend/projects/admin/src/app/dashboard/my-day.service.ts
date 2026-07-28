import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient, Money } from 'core';

/**
 * A salesperson's "My Day" snapshot (mirrors the backend {@code MyDayResponse}):
 * today + month-to-date figures vs. their monthly target, and payments to chase.
 */
export interface MyDay {
  ordersToday: number;
  revenueToday: Money;
  monthOrders: number;
  monthRevenue: Money;
  monthTarget: Money | null;
  targetProgressPct: number;
  pendingPaymentsCount: number;
  pendingPaymentsAmount: Money;
}

/** A lapsed customer on the win-back call list (mirrors {@code WinBackCustomer}). */
export interface WinBackCustomer {
  mobile: string;
  customerName: string | null;
  lastOrderDate: string | null;
  daysSinceLastOrder: number;
  orderCount: number;
  totalValue: Money;
}

/** One ranked row on the sales leaderboard (mirrors {@code LeaderboardRow}). */
export interface LeaderboardRow {
  rank: number;
  salespersonId: number;
  name: string;
  revenue: Money;
  orders: number;
  isMe: boolean;
}

/** This-month leaderboard + the caller's standing (mirrors {@code LeaderboardResponse}). */
export interface Leaderboard {
  rows: LeaderboardRow[];
  myRank: number | null;
  myRevenue: Money;
  myStreakDays: number;
}

/** A customer predicted to be due for a repeat order (mirrors {@code ReorderDueCustomer}). */
export interface ReorderDueCustomer {
  mobile: string;
  customerName: string | null;
  lastOrderDate: string | null;
  predictedReorderDate: string | null;
  avgIntervalDays: number;
  /** Positive = overdue by that many days; negative = due in that many days. */
  overdueDays: number;
  orderCount: number;
  totalValue: Money;
}

/**
 * Salesperson self-service data ({@code /api/my-day}) for the "My Day" home card
 * and the win-back call list. Scoped server-side to the caller's own orders.
 */
@Injectable({ providedIn: 'root' })
export class MyDayService {
  private readonly api = inject(ApiClient);

  /** Today + month-to-date figures vs. target, and payments to chase. */
  myDay(): Observable<MyDay> {
    return this.api.get<MyDay>('/api/my-day');
  }

  /** Lapsed customers (no order in the last {@code days} days), highest value first. */
  winBack(days?: number): Observable<WinBackCustomer[]> {
    return this.api.get<WinBackCustomer[]>(
      '/api/my-day/win-back',
      days ? { params: { days } } : undefined,
    );
  }

  /** Customers predicted (from cadence) to be due for a repeat order, most overdue first. */
  reorderDue(): Observable<ReorderDueCustomer[]> {
    return this.api.get<ReorderDueCustomer[]>('/api/my-day/reorder-due');
  }

  /** This-month sales leaderboard + the caller's rank and order streak. */
  leaderboard(): Observable<Leaderboard> {
    return this.api.get<Leaderboard>('/api/my-day/leaderboard');
  }
}
