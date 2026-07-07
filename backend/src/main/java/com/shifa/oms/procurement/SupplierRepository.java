package com.shifa.oms.procurement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data repository for {@link Supplier} entities (Feature C2).
 */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    /** All suppliers, ordered by name (admin management grid). */
    List<Supplier> findAllByOrderByNameAsc();

    /** Active suppliers only, ordered by name (the PO supplier picker). */
    List<Supplier> findByActiveTrueOrderByNameAsc();
}
