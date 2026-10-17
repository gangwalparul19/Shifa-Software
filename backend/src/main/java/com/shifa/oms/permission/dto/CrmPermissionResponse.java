package com.shifa.oms.permission.dto;

import com.shifa.oms.permission.CrmPermission;

/** A configurable CRM permission grant (CRM expansion Phase 3, Req 19). */
public record CrmPermissionResponse(Long id, String permission, String role, Long userId,
                                    boolean granted) {

    public static CrmPermissionResponse from(CrmPermission p) {
        return new CrmPermissionResponse(p.getId(), p.getPermission(), p.getRole(),
                p.getUserId(), p.isGranted());
    }
}
