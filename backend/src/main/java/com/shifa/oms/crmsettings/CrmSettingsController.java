package com.shifa.oms.crmsettings;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.crmsettings.dto.CrmSettingsRequest;
import com.shifa.oms.crmsettings.dto.CrmSettingsResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin CRM-settings endpoints (CRM expansion Phase 3, Req 18.3). ADMIN-only.
 */
@RestController
@RequestMapping("/api/admin/crm-settings")
@PreAuthorize("hasRole('ADMIN')")
public class CrmSettingsController {

    private final CrmSettingsService crmSettingsService;
    private final AuditService auditService;

    public CrmSettingsController(CrmSettingsService crmSettingsService, AuditService auditService) {
        this.crmSettingsService = crmSettingsService;
        this.auditService = auditService;
    }

    /** The current CRM settings (create-on-missing with defaults). */
    @GetMapping
    public CrmSettingsResponse get() {
        return CrmSettingsResponse.from(crmSettingsService.getSettings());
    }

    /** Updates the CRM settings (follow-up default time, grace window, default pipeline, rule). */
    @PutMapping
    public CrmSettingsResponse update(@Valid @RequestBody CrmSettingsRequest request) {
        CrmSettingsResponse response = CrmSettingsResponse.from(crmSettingsService.update(request));
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS,
                null, "Updated CRM settings");
        return response;
    }
}
