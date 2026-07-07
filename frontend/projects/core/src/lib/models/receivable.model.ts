import { Money } from './money.model';

/** Kind of receivable in the reconciliation ledger (backend Req 16.2, 17.2). */
export enum ReceivableType {
  /** COD amount owed by a courier after a delivered COD order. */
  COD_RECEIVABLE = 'COD_RECEIVABLE',
  /** Claim raised against a courier for a lost/damaged shipment. */
  CLAIM_RECEIVABLE = 'CLAIM_RECEIVABLE',
}

/** Mirrors the backend Receivable DTO (`receivables` table). */
export interface Receivable {
  id: number;
  orderId: number;
  courierCompanyId: number;
  type: ReceivableType;
  amount: Money;
  settled: boolean;
  settledDate?: string;
  createdAt?: string;
}
