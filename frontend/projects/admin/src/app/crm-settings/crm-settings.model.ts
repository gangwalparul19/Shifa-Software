/**
 * Client-side models for the CRM configuration area (crm-expansion Phase 3,
 * Req 16–19): pipelines + stages, managed lead tags, managed lead sources, CRM
 * defaults, and the configurable permission layer. Mirror the backend
 * {@code com.shifa.oms.pipeline} / {@code .lead} / {@code .crmsettings} /
 * {@code .permission} DTOs.
 */

/** A configurable pipeline stage (backend {@code PipelineStageResponse}). */
export interface PipelineStage {
  id: number;
  pipelineId: number;
  name: string;
  sortOrder: number;
  isEntry: boolean;
  terminalKind: string | null;
  legacyStatus: string | null;
  active: boolean;
}

/** A configurable pipeline with its stages (backend {@code PipelineResponse}). */
export interface Pipeline {
  id: number;
  name: string;
  isDefault: boolean;
  active: boolean;
  sortOrder: number;
  stages: PipelineStage[];
}

/** Create/rename a pipeline. */
export interface PipelineRequest {
  name: string;
  active?: boolean;
  sortOrder?: number;
}

/** Add/rename/retire a stage; terminalKind = null | WON | LOST | CLOSED. */
export interface PipelineStageRequest {
  name: string;
  isEntry?: boolean;
  terminalKind?: string | null;
  active?: boolean;
  sortOrder?: number;
}

/** A managed lead tag (backend {@code LeadTagResponse}). */
export interface LeadTagAdmin {
  id: number;
  name: string;
  color: string | null;
  kind: string;
  active: boolean;
  stageId: number | null;
}

/** Create/rename/retire a lead tag with an optional stage association. */
export interface LeadTagRequest {
  name: string;
  color?: string | null;
  active?: boolean;
  sortOrder?: number;
  stageId?: number | null;
}

/** A managed lead source (backend {@code LeadSourceResponse}). */
export interface LeadSourceAdmin {
  id: number;
  name: string;
  active: boolean;
  sortOrder: number;
}

/** Create/rename/activate a managed lead source. */
export interface LeadSourceRequest {
  name: string;
  active?: boolean;
  sortOrder?: number;
}

/** The single-row CRM settings (backend {@code CrmSettingsResponse}). */
export interface CrmSettings {
  followUpDefaultTime: string | null;
  missedGraceHours: number;
  defaultPipelineId: number | null;
  defaultAssignmentRule: string;
}

/** Update the CRM settings. */
export interface CrmSettingsRequest {
  followUpDefaultTime?: string | null;
  missedGraceHours?: number | null;
  defaultPipelineId?: number | null;
  defaultAssignmentRule?: string | null;
}

/** A configurable CRM permission grant (backend {@code CrmPermissionResponse}). */
export interface CrmPermission {
  id: number;
  permission: string;
  role: string | null;
  userId: number | null;
  granted: boolean;
}

/** Grant/revoke a CRM permission to a role or user. */
export interface CrmPermissionRequest {
  permission: string;
  role?: string | null;
  userId?: number | null;
  granted?: boolean;
}
