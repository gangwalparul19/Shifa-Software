package com.shifa.oms.lead;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.lead.dto.LeadImportResult;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Admin-only bulk lead upload (CRM expansion, Req 2). Mirrors the courier
 * remittance import surface ({@code ReconciliationController.importRemittance}):
 * a multipart CSV/Excel file is parsed, each row validated, and a lead created
 * per valid row — returning a per-row {@link LeadImportResult}. Restricted to
 * ADMIN (Req 2.5).
 */
@RestController
@RequestMapping("/api/admin/leads")
@PreAuthorize("hasRole('ADMIN')")
public class LeadImportController {

    private final LeadBulkImportService importService;
    private final CurrentUserService currentUserService;

    public LeadImportController(LeadBulkImportService importService,
                                CurrentUserService currentUserService) {
        this.importService = importService;
        this.currentUserService = currentUserService;
    }

    /**
     * Imports a {@code .csv}/{@code .xlsx}/{@code .xls} file of leads (Req 2.1).
     * The first row is the header; columns are matched case-insensitively by
     * alias (name/mobile/email/source/note/assignee/tags/followUpDate).
     */
    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or @perms.has(authentication,'BULK_UPLOAD')")
    public LeadImportResult importLeads(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("A CSV or Excel file is required.");
        }
        AuthPrincipal actor = currentUserService.requireCurrentUser();
        try {
            return importService.importFile(file.getBytes(), file.getOriginalFilename(), actor);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the uploaded file.", e);
        }
    }
}
