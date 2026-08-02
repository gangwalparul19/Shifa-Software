package com.shifa.oms.settings;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.platform.storage.StorageService;
import com.shifa.oms.settings.dto.SettingsRequest;
import com.shifa.oms.settings.dto.SettingsResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Admin-only company + GST settings endpoints.
 *
 * <p>Restricted to the {@code ADMIN} role via method security; unauthenticated
 * callers get 401 and non-admins 403 (rendered as the standard error envelope).
 * These settings control whether per-order invoices render as a plain invoice or
 * a GST tax invoice.
 */
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
public class SettingsController {

    private final SettingsService settingsService;
    private final CompanyLogoService companyLogoService;
    private final AuditService auditService;

    public SettingsController(SettingsService settingsService,
                              CompanyLogoService companyLogoService,
                              AuditService auditService) {
        this.settingsService = settingsService;
        this.companyLogoService = companyLogoService;
        this.auditService = auditService;
    }

    /** Returns the current company + GST settings (create-on-missing with defaults). */
    @GetMapping
    public SettingsResponse get() {
        return SettingsResponse.from(settingsService.getSettings());
    }

    /** Updates the company + GST settings; GSTIN is required when GST is enabled. */
    @PutMapping
    public SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
        SettingsService.UpdateResult result = settingsService.updateWithChanges(request);
        SettingsResponse response = SettingsResponse.from(result.settings());
        // Exactly one audit event, naming each changed shipment default so an admin
        // can see who changed the pickup warehouse or parcel size (Req 16.3).
        String detail = result.shipmentDefaultChanges().isEmpty()
                ? "Updated company / GST settings"
                : "Updated company / GST settings; shipment defaults: "
                        + String.join(", ", result.shipmentDefaultChanges());
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS,
                null, detail);
        return response;
    }

    /**
     * Uploads (or replaces) the company logo rendered on invoices and labels. The
     * multipart part must be a PNG or JPEG image up to 1 MB. Returns the updated
     * settings including the new {@code logoObjectKey}.
     */
    @PostMapping("/logo")
    public SettingsResponse uploadLogo(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A logo image file is required.");
        }
        try {
            return SettingsResponse.from(companyLogoService.uploadLogo(
                    file.getOriginalFilename(), file.getContentType(), file.getBytes()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded logo file.", e);
        }
    }

    /** Clears the configured company logo (PDFs fall back to the text brand). */
    @DeleteMapping("/logo")
    public SettingsResponse clearLogo() {
        return SettingsResponse.from(companyLogoService.clearLogo());
    }

    /** Returns the configured company logo image bytes, or 404 when none is configured. */
    @GetMapping("/logo")
    public ResponseEntity<ByteArrayResource> getLogo() {
        StorageService.StoredObject logo = companyLogoService.loadLogo()
                .orElseThrow(() -> new ResourceNotFoundException("No company logo is configured."));
        MediaType contentType = logo.contentType() != null
                ? MediaType.parseMediaType(logo.contentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"company-logo\"")
                .body(new ByteArrayResource(logo.content()));
    }
}
