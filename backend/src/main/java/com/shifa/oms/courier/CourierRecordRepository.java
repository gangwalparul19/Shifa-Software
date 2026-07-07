package com.shifa.oms.courier;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository for {@link CourierRecord} rows.
 *
 * <p>Lookups by order back assignment (one record per order) and the customer
 * tracking view; lookups by AWB back the tracking webhook (Req 13.1, 13.2); the
 * in-flight finder feeds the scheduled poll fallback that reconciles missed
 * webhooks.
 */
public interface CourierRecordRepository extends JpaRepository<CourierRecord, Long> {

    /** The courier record for an order, if one exists. */
    Optional<CourierRecord> findByOrderId(Long orderId);

    /** The courier record bearing an AWB, if one exists (webhook/poll lookup). */
    Optional<CourierRecord> findByAwb(String awb);

    /** All records that have an AWB assigned (poll fallback candidate set). */
    List<CourierRecord> findByAwbIsNotNull();
}
