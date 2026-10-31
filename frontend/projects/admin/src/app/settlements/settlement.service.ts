import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import { SettlementBatch, SettlementCandidate, SettlementImportResponse, SettlementKpi, SettlementLine, SettlementLineRequest } from './settlement.model';

@Injectable({ providedIn: 'root' })
export class SettlementService {
  private readonly api = inject(ApiClient);

  batches(): Observable<SettlementBatch[]> {
    return this.api.get<SettlementBatch[]>('/api/recon/settlements');
  }

  kpis(from?: string | null, to?: string | null, allTime = false): Observable<SettlementKpi> {
    const params: Record<string, string | boolean> = { allTime };
    if (from) params['from'] = from;
    if (to) params['to'] = to;
    return this.api.get<SettlementKpi>('/api/recon/settlements/kpis', { params });
  }

  lines(batchId: number): Observable<SettlementLine[]> {
    return this.api.get<SettlementLine[]>(`/api/recon/settlements/${batchId}/lines`);
  }

  candidates(lineId: number): Observable<SettlementCandidate[]> {
    return this.api.get<SettlementCandidate[]>(`/api/recon/settlements/lines/${lineId}/candidates`);
  }

  resolveLine(lineId: number, request: SettlementLineRequest): Observable<SettlementLine> {
    return this.api.post<SettlementLine>(`/api/recon/settlements/lines/${lineId}/resolve`, request);
  }
  importFile(file: File, source: string, dryRun: boolean, accountId?: number | null,
             reference?: string | null, statementDate?: string | null): Observable<SettlementImportResponse> {
    const form = new FormData();
    form.append('file', file);
    const params: Record<string, string | number | boolean> = { source, dryRun };
    if (accountId != null) params['destinationAccountId'] = accountId;
    if (reference?.trim()) params['externalReference'] = reference.trim();
    if (statementDate) params['statementDate'] = statementDate;
    return this.api.post<SettlementImportResponse>('/api/recon/settlements/import', form, { params });
  }
}
