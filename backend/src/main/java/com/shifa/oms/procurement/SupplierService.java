package com.shifa.oms.procurement;

import com.shifa.oms.audit.AuditActions;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.procurement.dto.SupplierRequest;
import com.shifa.oms.procurement.dto.SupplierResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Supplier / vendor application service (Feature C2).
 *
 * <p>Owns admin CRUD plus activate/deactivate. Deactivation is a soft toggle:
 * a supplier is never deleted (historical POs reference it), just hidden from
 * the active picker. Creation records a best-effort {@code SUPPLIER_CREATED}
 * audit event.
 */
@Service
public class SupplierService {

    private final SupplierRepository supplierRepository;
    private final AuditService auditService;

    public SupplierService(SupplierRepository supplierRepository, AuditService auditService) {
        this.supplierRepository = supplierRepository;
        this.auditService = auditService;
    }

    /** Creates a new (active) supplier. */
    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        Supplier supplier = new Supplier(
                request.name(), request.contactPerson(), request.phone(),
                request.email(), request.address());
        Supplier saved = supplierRepository.save(supplier);
        auditService.record(AuditActions.SUPPLIER_CREATED, AuditActions.ENTITY_SUPPLIER,
                String.valueOf(saved.getId()), "Supplier created: " + saved.getName());
        return SupplierResponse.from(saved);
    }

    /** Updates an existing supplier's editable fields. */
    @Transactional
    public SupplierResponse update(Long id, SupplierRequest request) {
        Supplier supplier = requireSupplier(id);
        supplier.update(request.name(), request.contactPerson(), request.phone(),
                request.email(), request.address());
        return SupplierResponse.from(supplierRepository.save(supplier));
    }

    /** Sets a supplier's active flag, returning the updated supplier. */
    @Transactional
    public SupplierResponse setActive(Long id, boolean active) {
        Supplier supplier = requireSupplier(id);
        supplier.setActive(active);
        return SupplierResponse.from(supplierRepository.save(supplier));
    }

    /** All suppliers ordered by name; when {@code activeOnly} only active ones. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> list(boolean activeOnly) {
        List<Supplier> suppliers = activeOnly
                ? supplierRepository.findByActiveTrueOrderByNameAsc()
                : supplierRepository.findAllByOrderByNameAsc();
        return suppliers.stream().map(SupplierResponse::from).toList();
    }

    /** A single supplier by id, or a 404. */
    @Transactional(readOnly = true)
    public SupplierResponse get(Long id) {
        return SupplierResponse.from(requireSupplier(id));
    }

    private Supplier requireSupplier(Long id) {
        return supplierRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Supplier " + id + " does not exist."));
    }
}
