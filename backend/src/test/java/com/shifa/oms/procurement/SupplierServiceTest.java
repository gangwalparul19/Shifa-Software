package com.shifa.oms.procurement;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.procurement.dto.SupplierRequest;
import com.shifa.oms.procurement.dto.SupplierResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Example-based unit tests for {@link SupplierService} (Feature C2). The
 * repository is a Mockito mock; the concrete {@link AuditService} is a
 * hand-written no-op fake (Mockito cannot mock the concrete service in this
 * project's setup).
 */
@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock
    private SupplierRepository supplierRepository;

    private SupplierService service;

    @BeforeEach
    void setUp() {
        service = new SupplierService(supplierRepository, new NoopAuditService());
        lenient().when(supplierRepository.save(any(Supplier.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createStoresActiveSupplier() {
        SupplierResponse response = service.create(new SupplierRequest(
                "Herbal Wholesale", "Ravi", "9876543210", "ravi@example.com", "Pune"));

        assertThat(response.name()).isEqualTo("Herbal Wholesale");
        assertThat(response.contactPerson()).isEqualTo("Ravi");
        assertThat(response.active()).isTrue();
    }

    @Test
    void updateChangesEditableFields() {
        Supplier existing = new Supplier("Old Name", null, null, null, null);
        when(supplierRepository.findById(3L)).thenReturn(Optional.of(existing));

        SupplierResponse response = service.update(3L, new SupplierRequest(
                "New Name", "Meena", "9000000000", "meena@example.com", "Mumbai"));

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.contactPerson()).isEqualTo("Meena");
        assertThat(response.phone()).isEqualTo("9000000000");
    }

    @Test
    void updateMissingSupplierIsNotFound() {
        when(supplierRepository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(9L, new SupplierRequest(
                "x", null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deactivateTogglesActiveFlag() {
        Supplier existing = new Supplier("Active Co", null, null, null, null);
        when(supplierRepository.findById(5L)).thenReturn(Optional.of(existing));

        SupplierResponse response = service.setActive(5L, false);

        assertThat(response.active()).isFalse();
    }

    @Test
    void listActiveOnlyUsesActiveFinder() {
        when(supplierRepository.findByActiveTrueOrderByNameAsc())
                .thenReturn(List.of(new Supplier("A", null, null, null, null)));

        List<SupplierResponse> result = service.list(true);

        assertThat(result).hasSize(1);
    }

    /** A no-op audit service so best-effort auditing never interferes with the test. */
    private static final class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public AuditEvent record(String action, String entityType, String entityId, String summary) {
            return null;
        }
    }
}
