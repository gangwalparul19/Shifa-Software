/**
 * Configurable CRM permission layer (CRM expansion Phase 3, Req 19). Grants
 * (role- or user-scoped) that {@code or}-widen the fixed base-role guard on CRM
 * endpoints via the SpEL bean {@code @perms} ({@link com.shifa.oms.permission.PermissionService}).
 * Safe defaults reproduce today's behaviour until an admin customizes them.
 */
package com.shifa.oms.permission;
