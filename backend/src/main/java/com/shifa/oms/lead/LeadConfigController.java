package com.shifa.oms.lead;

import com.shifa.oms.lead.dto.LeadSourceRequest;
import com.shifa.oms.lead.dto.LeadSourceResponse;
import com.shifa.oms.lead.dto.LeadTagRequest;
import com.shifa.oms.lead.dto.LeadTagResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin CRM-vocabulary CRUD + capture read endpoints (CRM expansion Phase 3,
 * Req 18): managed lead tags and lead sources.
 *
 * <ul>
 *   <li>{@code /api/admin/lead-tags} + {@code /api/admin/lead-sources} — ADMIN,
 *       widened by the {@code MANAGE_TAGS} / {@code MANAGE_SOURCES} permissions.</li>
 *   <li>{@code GET /api/lead-sources} — any lead role — the active capture picker
 *       (the Phase-1 {@code GET /api/leads/tags} stays the active-tags picker).</li>
 * </ul>
 */
@RestController
public class LeadConfigController {

    private final LeadConfigService leadConfigService;

    public LeadConfigController(LeadConfigService leadConfigService) {
        this.leadConfigService = leadConfigService;
    }

    // --- Capture pickers (any lead role) -----------------------------------

    /** The active managed lead sources for the capture picker. */
    @GetMapping("/api/lead-sources")
    @PreAuthorize("hasAnyRole('SALESPERSON','TEAM_LEAD','ADMIN')")
    public List<LeadSourceResponse> activeSources() {
        return leadConfigService.listActiveSources();
    }

    // --- Admin lead-tag CRUD -----------------------------------------------

    @GetMapping("/api/admin/lead-tags")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_TAGS')")
    public List<LeadTagResponse> listTags() {
        return leadConfigService.listAllTags();
    }

    @PostMapping("/api/admin/lead-tags")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_TAGS')")
    public LeadTagResponse createTag(@Valid @RequestBody LeadTagRequest request) {
        return leadConfigService.createTag(request);
    }

    @PutMapping("/api/admin/lead-tags/{id}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_TAGS')")
    public LeadTagResponse updateTag(@PathVariable Long id, @Valid @RequestBody LeadTagRequest request) {
        return leadConfigService.updateTag(id, request);
    }

    @DeleteMapping("/api/admin/lead-tags/{id}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_TAGS')")
    public LeadTagResponse retireTag(@PathVariable Long id) {
        return leadConfigService.retireTag(id);
    }

    // --- Admin lead-source CRUD --------------------------------------------

    @GetMapping("/api/admin/lead-sources")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_SOURCES')")
    public List<LeadSourceResponse> listSources() {
        return leadConfigService.listAllSources();
    }

    @PostMapping("/api/admin/lead-sources")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_SOURCES')")
    public LeadSourceResponse createSource(@Valid @RequestBody LeadSourceRequest request) {
        return leadConfigService.createSource(request);
    }

    @PutMapping("/api/admin/lead-sources/{id}")
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'MANAGE_SOURCES')")
    public LeadSourceResponse updateSource(@PathVariable Long id,
                                           @Valid @RequestBody LeadSourceRequest request) {
        return leadConfigService.updateSource(id, request);
    }
}
