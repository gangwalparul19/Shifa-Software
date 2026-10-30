import { Money } from 'core';

export type SettlementSource = 'BANK_RECEIPT' | 'SHOPIFY_PAYOUT' | 'QUICKSHIPX_COD';
export type SettlementBatchStatus = 'PROCESSING' | 'POSTED' | 'POSTED_WITH_EXCEPTIONS' | 'VOID';
export type SettlementMatchStatus = 'MATCHED' | 'MISMATCH' | 'UNMATCHED' | 'AMBIGUOUS' | 'DUPLICATE_IN_FILE' | 'INVALID' | 'RESOLVED';
export type SettlementMatchMethod = 'UTR' | 'SHOPIFY_ORDER_ID' | 'ORDER_CODE' | 'AWB' | 'QUICKSHIP_CLIENT_ORDER_ID';

export interface SettlementBatch {
  id: number;
  sourceChannel: SettlementSource;
  externalReference?: string | null;
  statementDate?: string | null;
  destinationAccountId?: number | null;
  destinationAccountName?: string | null;
  originalFilename?: string | null;
  status: SettlementBatchStatus;
  lineCount: number;
  totalAmount: Money;
  allocatedAmount: Money;
  suspenseAmount: Money;
  createdAt?: string | null;
  postedAt?: string | null;
}

export interface SettlementLine {
  id?: number | null;
  sourceRowNumber: number;
  orderCode?: string | null;
  shopifyOrderId?: string | null;
  awb?: string | null;
  utr?: string | null;
  transactionDate?: string | null;
  amount: Money;
  expectedAmount?: Money | null;
  variance: Money;
  allocatedAmount: Money;
  suspenseAmount: Money;
  matchStatus: SettlementMatchStatus;
  matchMethod?: SettlementMatchMethod | null;
  resolvedOrderId?: number | null;
  resolvedPaymentId?: number | null;
  resolvedReceivableId?: number | null;
  message?: string | null;
}

export interface SettlementImportResponse {
  dryRun: boolean;
  batchId?: number | null;
  sourceChannel: SettlementSource;
  externalReference?: string | null;
  statementDate?: string | null;
  totalRows: number;
  totalAmount: Money;
  allocatedAmount: Money;
  suspenseAmount: Money;
  counts: Partial<Record<SettlementMatchStatus, number>>;
  lines: SettlementLine[];
}

export interface AccountSettlementKpi {
  accountId: number | null;
  accountName: string;
  omsBookedAmount: Money;
  verifiedAmount: Money;
  pendingAmount: Money;
  rejectedAmount: Money;
  unreviewedAmount: Money;
  bankStatementAmount: Money;
  bankMatchedAmount: Money;
  bankUnmatchedAmount: Money;
  difference: Money;
  statementRows: number;
  matchedRows: number;
  unmatchedRows: number;
}

export interface SettlementKpi {
  from?: string | null;
  to?: string | null;
  omsBookedAmount: Money;
  bankStatementAmount: Money;
  bankMatchedAmount: Money;
  bankUnmatchedAmount: Money;
  difference: Money;
  verifiedAmount: Money;
  pendingAmount: Money;
  rejectedAmount: Money;
  unreviewedAmount: Money;
  accounts: AccountSettlementKpi[];
}
