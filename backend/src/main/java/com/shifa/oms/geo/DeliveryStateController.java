package com.shifa.oms.geo;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.geo.dto.DeliveryStateRequest;
import com.shifa.oms.geo.dto.DeliveryStateResponse;
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
 * Delivery-state endpoints.
 *
 * <p>The read endpoint {@code GET /api/states} returns the active state names and
 * is open to any authenticated staff member — it powers the state typeahead on
 * the salesperson New Order form. The management endpoints under
 * {@code /api/admin/states} (list-all / create / update / delete) are
 * {@code ADMIN}-only and drive the "Delivery States" section of the Settings
 * page. Both live under {@code /api/**}, which the security config already
 * requires authentication for.
 */
@RestController
public class DeliveryStateController {

    private final DeliveryStateService service;
    private final AuditService auditService;

    public DeliveryStateController(DeliveryStateService service, AuditService auditService) {
        this.service = service;
        this.auditService = auditService;
    }

    /** Active state names for the order-entry typeahead (any authenticated staff). */
    @GetMapping("/api/states")
    public List<String> listActiveNames() {
        return service.activeNames();
    }

    /** All states (active + inactive) for the admin Settings management table. */
    @GetMapping("/api/admin/states")
    @PreAuthorize("hasRole('ADMIN')")
    public List<DeliveryStateResponse> listAll() {
        return service.listAll().stream().map(DeliveryStateResponse::from).toList();
    }

    /** Adds a new delivery state. */
    @PostMapping("/api/admin/states")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public DeliveryStateResponse create(@Valid @RequestBody DeliveryStateRequest request) {
        DeliveryStateResponse response = DeliveryStateResponse.from(service.create(request));
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS,
                String.valueOf(response.id()), "Added delivery state \"" + response.name() + "\"");
        return response;
    }

    /** Renames / re-orders / enables-disables a delivery state. */
    @PutMapping("/api/admin/states/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DeliveryStateResponse update(@PathVariable Long id,
                                        @Valid @RequestBody DeliveryStateRequest request) {
        DeliveryStateResponse response = DeliveryStateResponse.from(service.update(id, request));
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS,
                String.valueOf(response.id()), "Updated delivery state \"" + response.name() + "\"");
        return response;
    }

    /** Removes a delivery state from the master list. */
    @DeleteMapping("/api/admin/states/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
        auditService.record(AuditActions.SETTINGS_UPDATED, AuditActions.ENTITY_SETTINGS,
                String.valueOf(id), "Removed delivery state " + id);
    }
}
