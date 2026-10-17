import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { ApiClient } from 'core';
import {
  CrmPermission,
  CrmPermissionRequest,
  CrmSettings,
  CrmSettingsRequest,
  LeadSourceAdmin,
  LeadSourceRequest,
  LeadTagAdmin,
  LeadTagRequest,
  Pipeline,
  PipelineRequest,
  PipelineStage,
  PipelineStageRequest,
} from './crm-settings.model';

/**
 * Data access for the admin CRM configuration area (crm-expansion Phase 3).
 * Admin CRUD is enforced server-side (ADMIN or the matching CRM permission);
 * the frontend also hides actions the caller lacks via {@link myPermissions}.
 */
@Injectable({ providedIn: 'root' })
export class CrmSettingsService {
  private readonly api = inject(ApiClient);

  // --- Pipelines & stages ------------------------------------------------

  /** All pipelines + stages (admin CRUD view). */
  listPipelines(): Observable<Pipeline[]> {
    return this.api.get<Pipeline[]>('/api/admin/pipelines');
  }

  /** Active pipelines + active stages (board / capture pickers). */
  activePipelines(): Observable<Pipeline[]> {
    return this.api.get<Pipeline[]>('/api/pipelines');
  }

  createPipeline(payload: PipelineRequest): Observable<Pipeline> {
    return this.api.post<Pipeline>('/api/admin/pipelines', payload);
  }

  updatePipeline(id: number, payload: PipelineRequest): Observable<Pipeline> {
    return this.api.put<Pipeline>(`/api/admin/pipelines/${id}`, payload);
  }

  addStage(pipelineId: number, payload: PipelineStageRequest): Observable<PipelineStage> {
    return this.api.post<PipelineStage>(`/api/admin/pipelines/${pipelineId}/stages`, payload);
  }

  updateStage(
    pipelineId: number,
    stageId: number,
    payload: PipelineStageRequest,
  ): Observable<PipelineStage> {
    return this.api.put<PipelineStage>(
      `/api/admin/pipelines/${pipelineId}/stages/${stageId}`,
      payload,
    );
  }

  reorderStages(pipelineId: number, stageIds: number[]): Observable<PipelineStage[]> {
    return this.api.put<PipelineStage[]>(`/api/admin/pipelines/${pipelineId}/stages/reorder`, {
      stageIds,
    });
  }

  retireStage(pipelineId: number, stageId: number): Observable<PipelineStage> {
    return this.api.delete<PipelineStage>(`/api/admin/pipelines/${pipelineId}/stages/${stageId}`);
  }

  // --- Lead tags (admin CRUD) --------------------------------------------

  listTags(): Observable<LeadTagAdmin[]> {
    return this.api.get<LeadTagAdmin[]>('/api/admin/lead-tags');
  }

  createTag(payload: LeadTagRequest): Observable<LeadTagAdmin> {
    return this.api.post<LeadTagAdmin>('/api/admin/lead-tags', payload);
  }

  updateTag(id: number, payload: LeadTagRequest): Observable<LeadTagAdmin> {
    return this.api.put<LeadTagAdmin>(`/api/admin/lead-tags/${id}`, payload);
  }

  retireTag(id: number): Observable<LeadTagAdmin> {
    return this.api.delete<LeadTagAdmin>(`/api/admin/lead-tags/${id}`);
  }

  // --- Lead sources (admin CRUD) -----------------------------------------

  listSources(): Observable<LeadSourceAdmin[]> {
    return this.api.get<LeadSourceAdmin[]>('/api/admin/lead-sources');
  }

  /** Active sources (capture picker). */
  activeSources(): Observable<LeadSourceAdmin[]> {
    return this.api.get<LeadSourceAdmin[]>('/api/lead-sources');
  }

  createSource(payload: LeadSourceRequest): Observable<LeadSourceAdmin> {
    return this.api.post<LeadSourceAdmin>('/api/admin/lead-sources', payload);
  }

  updateSource(id: number, payload: LeadSourceRequest): Observable<LeadSourceAdmin> {
    return this.api.put<LeadSourceAdmin>(`/api/admin/lead-sources/${id}`, payload);
  }

  // --- CRM settings ------------------------------------------------------

  getSettings(): Observable<CrmSettings> {
    return this.api.get<CrmSettings>('/api/admin/crm-settings');
  }

  updateSettings(payload: CrmSettingsRequest): Observable<CrmSettings> {
    return this.api.put<CrmSettings>('/api/admin/crm-settings', payload);
  }

  // --- Permissions -------------------------------------------------------

  permissionDefinitions(): Observable<string[]> {
    return this.api.get<string[]>('/api/admin/crm-permissions/definitions');
  }

  listPermissions(): Observable<CrmPermission[]> {
    return this.api.get<CrmPermission[]>('/api/admin/crm-permissions');
  }

  grantPermission(payload: CrmPermissionRequest): Observable<CrmPermission> {
    return this.api.post<CrmPermission>('/api/admin/crm-permissions', payload);
  }

  revokePermission(id: number): Observable<void> {
    return this.api.delete<void>(`/api/admin/crm-permissions/${id}`);
  }

  /** The caller's effective CRM permission set (to hide actions). */
  myPermissions(): Observable<string[]> {
    return this.api.get<string[]>('/api/me/crm-permissions');
  }
}
