import { OrderStatus } from 'core';

export type ExceptionCategory = 'APPROVAL' | 'PAYMENT' | 'DELIVERY' | 'CLAIM' | 'INSIGHT';
export type ExceptionSeverity = 'HIGH' | 'MEDIUM' | 'DANGER' | 'WARNING' | 'INFO' | string;

export interface AdminExceptionItem {
  category: ExceptionCategory;
  severity: ExceptionSeverity;
  title: string;
  detail?: string | null;
  orderId?: number | null;
  orderCode?: string | null;
  customerName?: string | null;
  customerMobile?: string | null;
  orderStatus?: OrderStatus | null;
  amount?: string | number | null;
  createdAt?: string | null;
  actionPath: string;
}

export interface AdminExceptionResponse {
  total: number;
  countsByCategory: Record<string, number>;
  items: AdminExceptionItem[];
}

export const EXCEPTION_CATEGORY_LABELS: Record<ExceptionCategory, string> = {
  APPROVAL: 'Approval',
  PAYMENT: 'Payment',
  DELIVERY: 'Delivery',
  CLAIM: 'Courier claims',
  INSIGHT: 'Insights',
};
