package com.shifa.oms.geo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Data access for {@link DeliveryState}. Ordering is by {@code sortOrder} then
 * {@code name} so an admin can pin a few common states to the top while the rest
 * stay alphabetical.
 */
public interface DeliveryStateRepository extends JpaRepository<DeliveryState, Long> {

    /** All states (active + inactive) for admin management, ordered for display. */
    List<DeliveryState> findAllByOrderBySortOrderAscNameAsc();

    /** Only the active states, for the order-entry typeahead, ordered for display. */
    List<DeliveryState> findByActiveTrueOrderBySortOrderAscNameAsc();

    /** Case-insensitive lookup used to guard against duplicate names. */
    Optional<DeliveryState> findByNameIgnoreCase(String name);
}
