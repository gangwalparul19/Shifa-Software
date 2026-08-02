package com.shifa.oms.integration.quikshipx;

import com.shifa.oms.audit.AuditEvent;
import com.shifa.oms.audit.AuditService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, status mirroring (Req 6).
 *
 * <p>Checks the behaviour the client asked for: a QuikShipX status update lands on the right
 * shipment by any of its identifiers, moves the status forward, and is monotonic so a
 * stale/duplicate event cannot move it backwards.
 */
class QuikShipXStatusUpdateServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.parse("2026-08-02T10:00:00");

    @Test
    void aStatusEventAdvancesTheShipmentAndResolvesByQuikShipXOrderId() {
        Fixture f = new Fixture();
        OrderShipment shipment = f.save(shipment(1124L, "SHIFA-SHR-Z096", "65580852235", "177286"));
        shipment.advanceStatus(OrderShipment.INITIAL_STATUS, T0);

        QuikShipXStatusUpdateService.Result result = f.service.apply(
                "177286", null, null, "Label Printed", T0.plusHours(1));

        assertThat(result.applied()).isTrue();
        assertThat(result.orderId()).isEqualTo(1124L);
        assertThat(f.stored.get(shipment.getId()).getLastStatusToken()).isEqualTo("Label Printed");
    }

    @Test
    void theOrderReferenceIsAUsableFallbackWhenNoIdMatches() {
        Fixture f = new Fixture();
        f.save(shipment(1125L, "SHIFA-SHR-0R8H", "999", "555"));

        QuikShipXStatusUpdateService.Result result = f.service.apply(
                null, null, "SHIFA-SHR-0R8H", "In Transit", T0);

        assertThat(result.applied()).isTrue();
        assertThat(result.orderId()).isEqualTo(1125L);
    }

    @Test
    void anOlderOrEqualEventIsSupersededNotAppliedBackwards() {
        Fixture f = new Fixture();
        OrderShipment shipment = f.save(shipment(1126L, "SHIFA-SHR-TA82", "89807170871", "177293"));
        shipment.advanceStatus("In Transit", T0.plusHours(2));

        // An out-of-order "Pending" arriving late must not roll the status back.
        QuikShipXStatusUpdateService.Result result = f.service.apply(
                "177293", null, null, "Pending", T0);

        assertThat(result.outcome()).isEqualTo(QuikShipXStatusUpdateService.Outcome.SUPERSEDED);
        assertThat(f.stored.get(shipment.getId()).getLastStatusToken()).isEqualTo("In Transit");
    }

    @Test
    void anUnmatchedEventReportsUnknownShipment() {
        Fixture f = new Fixture();

        QuikShipXStatusUpdateService.Result result = f.service.apply(
                "does-not-exist", null, null, "Delivered", T0);

        assertThat(result.outcome()).isEqualTo(QuikShipXStatusUpdateService.Outcome.UNKNOWN_SHIPMENT);
    }

    @Test
    void aBlankStatusIsRejected() {
        Fixture f = new Fixture();
        f.save(shipment(1L, "SHIFA-1", "s", "o"));

        assertThat(f.service.apply("o", null, null, "  ", T0).outcome())
                .isEqualTo(QuikShipXStatusUpdateService.Outcome.NO_STATUS);
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static OrderShipment shipment(Long orderId, String reference,
                                          String shipmentId, String quikshipxOrderId) {
        OrderShipment shipment = OrderShipment.from(orderId, new ShipmentAcceptance(
                reference, shipmentId, quikshipxOrderId, null, null, null, null, true, null));
        return shipment;
    }

    private static final class Fixture {

        private final Map<Long, OrderShipment> stored = new LinkedHashMap<>();
        private final AtomicLong ids = new AtomicLong();
        private final QuikShipXStatusUpdateService service =
                new QuikShipXStatusUpdateService(repository(), new SilentAudit());

        OrderShipment save(OrderShipment shipment) {
            if (shipment.getId() == null) {
                ReflectionTestUtils.setField(shipment, "id", ids.incrementAndGet());
            }
            stored.put(shipment.getId(), shipment);
            return shipment;
        }

        private OrderShipmentRepository repository() {
            return (OrderShipmentRepository) Proxy.newProxyInstance(
                    OrderShipmentRepository.class.getClassLoader(),
                    new Class<?>[]{OrderShipmentRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "findByQuikshipxOrderId" -> stored.values().stream()
                                .filter(s -> args[0].equals(s.getQuikshipxOrderId())).findFirst();
                        case "findByQuikshipxShipmentId" -> stored.values().stream()
                                .filter(s -> args[0].equals(s.getQuikshipxShipmentId())).findFirst();
                        case "findByOrderReference" -> stored.values().stream()
                                .filter(s -> args[0].equals(s.getOrderReference())).findFirst();
                        case "save", "saveAndFlush" -> save((OrderShipment) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static final class SilentAudit extends AuditService {
        SilentAudit() {
            super(null, null);
        }

        @Override
        public AuditEvent record(Long actorUserId, String actorUsername, String action,
                                 String entityType, String entityId, String summary) {
            return null;
        }
    }
}
