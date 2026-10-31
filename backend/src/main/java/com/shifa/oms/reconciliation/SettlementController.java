package com.shifa.oms.reconciliation;

import com.shifa.oms.auth.AuthPrincipal;
import com.shifa.oms.auth.CurrentUserService;
import com.shifa.oms.common.ValidationException;
import com.shifa.oms.reconciliation.dto.SettlementBatchResponse;
import com.shifa.oms.reconciliation.dto.SettlementImportResponse;
import com.shifa.oms.reconciliation.dto.SettlementLineRequest;
import com.shifa.oms.reconciliation.dto.SettlementLineResponse;
import com.shifa.oms.reconciliation.dto.SettlementCandidateResponse;
import com.shifa.oms.reconciliation.dto.SettlementKpiResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;

/** Operational settlement-batch import/review endpoints (V98). */
@RestController
@RequestMapping("/api/recon/settlements")
@PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','CA')")
public class SettlementController {

    private final SettlementService service;
    private final SettlementKpiService kpiService;
    private final CurrentUserService currentUserService;

    public SettlementController(SettlementService service, SettlementKpiService kpiService,
                                CurrentUserService currentUserService) {
        this.service = service;
        this.kpiService = kpiService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/kpis")
    public SettlementKpiResponse kpis(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean allTime) {
        return kpiService.kpis(from, to, allTime);
    }

    @GetMapping
    public List<SettlementBatchResponse> batches() {
        return service.listBatches();
    }

    @GetMapping("/{id}/lines")
    public List<SettlementLineResponse> lines(@PathVariable Long id) {
        return service.lines(id);
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public SettlementImportResponse importFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam SettlementSource source,
            @RequestParam(required = false) Long destinationAccountId,
            @RequestParam(required = false) String externalReference,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statementDate,
            @RequestParam(name = "dryRun", defaultValue = "true") boolean dryRun) {
        if (file == null || file.isEmpty()) throw new ValidationException("A settlement file is required.");
        try {
            AuthPrincipal actor = currentUserService.requireCurrentUser();
            return service.importFile(file.getBytes(), file.getOriginalFilename(), source, destinationAccountId,
                    externalReference, statementDate, dryRun, actor);
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read the settlement file.", ex);
        }
    }

    @GetMapping("/lines/{id}/candidates")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT','CA')")
    public List<SettlementCandidateResponse> candidates(@PathVariable Long id) {
        return service.candidatesForLine(id);
    }

    @PostMapping("/lines/{id}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','ACCOUNTANT')")
    public SettlementLineResponse resolve(@PathVariable Long id, @Valid @RequestBody SettlementLineRequest request) {
        return service.resolve(id, request, currentUserService.requireCurrentUser());
    }
}
