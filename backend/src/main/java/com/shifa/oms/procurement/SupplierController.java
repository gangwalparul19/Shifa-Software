package com.shifa.oms.procurement;

import com.shifa.oms.procurement.dto.SupplierRequest;
import com.shifa.oms.procurement.dto.SupplierResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Admin supplier management endpoints ({@code /api/admin/suppliers}, Feature C2).
 * ADMIN only, matching the other admin management controllers.
 */
@RestController
@RequestMapping("/api/admin/suppliers")
@PreAuthorize("hasRole('ADMIN')")
public class SupplierController {

    private final SupplierService supplierService;

    public SupplierController(SupplierService supplierService) {
        this.supplierService = supplierService;
    }

    /**
     * Lists suppliers ordered by name. When {@code activeOnly=true} only active
     * suppliers are returned (the PO supplier picker).
     */
    @GetMapping
    public List<SupplierResponse> list(
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly) {
        return supplierService.list(activeOnly);
    }

    /** A single supplier by id. */
    @GetMapping("/{id}")
    public SupplierResponse get(@PathVariable Long id) {
        return supplierService.get(id);
    }

    /** Creates a new supplier. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SupplierResponse create(@Valid @RequestBody SupplierRequest request) {
        return supplierService.create(request);
    }

    /** Updates an existing supplier. */
    @PutMapping("/{id}")
    public SupplierResponse update(@PathVariable Long id, @Valid @RequestBody SupplierRequest request) {
        return supplierService.update(id, request);
    }

    /** Activates a supplier. */
    @PostMapping("/{id}/activate")
    public SupplierResponse activate(@PathVariable Long id) {
        return supplierService.setActive(id, true);
    }

    /** Deactivates a supplier (hidden from the active picker; historical POs kept). */
    @PostMapping("/{id}/deactivate")
    public SupplierResponse deactivate(@PathVariable Long id) {
        return supplierService.setActive(id, false);
    }
}
