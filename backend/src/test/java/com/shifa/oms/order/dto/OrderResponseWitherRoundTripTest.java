package com.shifa.oms.order.dto;

import com.shifa.oms.order.DeliveryMethod;
import com.shifa.oms.order.LeadSource;
import com.shifa.oms.order.OrderSource;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.RejectReason;
import com.shifa.oms.order.RtoReason;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the hand-written {@link OrderResponse} copy "withers"
 * ({@code withSalesperson}, {@code withQuikShip}, {@code withShipment},
 * {@code withCourierName}) against the silent positional-swap / dropped-field
 * risk flagged in ARCHITECTURE.md §12 R1: {@code OrderResponse} is a 51-component
 * record and each wither re-lists every component by hand, so inserting or
 * reordering a field can quietly send a value into the wrong slot (adjacent
 * strings like {@code awb}/{@code courierName}/{@code trackingUrl} are especially
 * easy to swap).
 *
 * <p>The test builds a fully-distinct instance (every component a unique,
 * identifiable value), calls each wither, and asserts via reflection that EVERY
 * component is either the wither's intended new value or byte-for-byte unchanged
 * from the original. A positional swap moves a value into the wrong component and
 * fails the "unchanged" assertion; a dropped field fails too. If a future field
 * is added to the record but not to a wither, this test won't compile (the
 * canonical constructor call below changes arity) — forcing the author to update
 * every wither.
 */
class OrderResponseWitherRoundTripTest {

    /** A base response with every component set to a distinct, recognisable value. */
    private static OrderResponse base() {
        return new OrderResponse(
                1L,                                   // id
                "SHR-BASE",                           // orderCode
                OrderSource.SALESPERSON,              // source
                DeliveryMethod.QUIKSHIPX,             // deliveryMethod
                LeadSource.WHATSAPP,                  // leadSource
                "lead-note",                          // leadSourceNote
                "cust@example.com",                   // customerEmail
                "order-notes",                        // notes
                OrderStatus.APPROVED,                 // orderStatus
                PaymentStatus.PARTIALLY_PAID,         // paymentStatus
                "Asha Kumari",                        // customerName
                "9000000001",                         // customerMobile
                "9000000002",                         // alternateMobile
                "12 Baker Street",                    // addressLine
                "Indore",                             // city
                "Madhya Pradesh",                     // state
                "452001",                             // postalCode
                new BigDecimal("1000.00"),            // totalAmount
                new BigDecimal("400.00"),             // amountReceived
                new BigDecimal("600.00"),             // remainingAmount
                new BigDecimal("600.00"),             // codAmount
                new BigDecimal("600.00"),             // customerOutstanding
                "SAVE10",                             // couponCode
                new BigDecimal("50.00"),              // discountAmount
                true,                                 // paymentScreenshotAvailable
                List.of(),                            // items
                LocalDateTime.of(2026, 1, 2, 3, 4, 5),// createdAt
                "BASE-AWB",                           // awb
                "BASE-COURIER",                       // courierName
                "https://track/base",                 // trackingUrl
                LocalDate.of(2026, 1, 9),             // estimatedDelivery
                "BASE-HANDOVER-NAME",                 // handoverName
                3,                                    // packageCount
                PaymentVerificationStatus.PENDING,    // paymentVerificationStatus
                new BigDecimal("950.00"),             // subtotalAmount
                new BigDecimal("45.00"),              // gstAmount
                "FLAT",                               // discountType
                new BigDecimal("50.00"),              // discountValue
                "27ABCDE1234F1Z5",                    // buyerGstin
                "BASE-QSX-STATUS",                    // quikShipXStatus
                "https://qsx/label/base",             // quikShipXLabelUrl
                "QSX-ORDER-BASE",                     // quikShipXOrderId
                true,                                 // quikShipXTest
                "base rejection reason",              // rejectionReason
                RtoReason.OTHER,                      // rtoReason
                "base rto note",                      // rtoReasonNote
                "MP09-BASE",                          // vehicleNumber
                "9000000003",                         // handoverPhone
                List.of(),                            // statusHistory
                "BASE-SALESPERSON",                   // salespersonName
                RejectReason.PAYMENT_ISSUE,           // rejectReason
                "base payment verification note",     // paymentVerificationNote
                "Nepal",                              // country
                "base qsx failure reason",            // quikShipXFailureReason
                "BASE-TRACK-TOKEN");                  // trackingToken
    }

    @Test
    void withSalespersonChangesOnlySalespersonName() {
        OrderResponse base = base();
        OrderResponse out = base.withSalesperson("NEW-SALESPERSON");

        assertThat(out.salespersonName()).isEqualTo("NEW-SALESPERSON");
        assertAllComponentsPreservedExcept(base, out, Set.of("salespersonName"));
    }

    @Test
    void withQuikShipChangesOnlyTheFourQuikShipFields() {
        OrderResponse base = base();
        OrderResponse out = base.withQuikShip("NEW-STATUS", "https://qsx/label/new", "QSX-NEW", false);

        assertThat(out.quikShipXStatus()).isEqualTo("NEW-STATUS");
        assertThat(out.quikShipXLabelUrl()).isEqualTo("https://qsx/label/new");
        assertThat(out.quikShipXOrderId()).isEqualTo("QSX-NEW");
        assertThat(out.quikShipXTest()).isFalse();
        assertAllComponentsPreservedExcept(base, out,
                Set.of("quikShipXStatus", "quikShipXLabelUrl", "quikShipXOrderId", "quikShipXTest"));
    }

    @Test
    void withShipmentChangesOnlyTheFourShipmentFields() {
        OrderResponse base = base();
        LocalDate eta = LocalDate.of(2026, 2, 20);
        OrderResponse out = base.withShipment("NEW-AWB", "NEW-COURIER", "https://track/new", eta);

        assertThat(out.awb()).isEqualTo("NEW-AWB");
        assertThat(out.courierName()).isEqualTo("NEW-COURIER");
        assertThat(out.trackingUrl()).isEqualTo("https://track/new");
        assertThat(out.estimatedDelivery()).isEqualTo(eta);
        assertAllComponentsPreservedExcept(base, out,
                Set.of("awb", "courierName", "trackingUrl", "estimatedDelivery"));
    }

    @Test
    void withCourierNameChangesOnlyCourierName() {
        OrderResponse base = base();
        OrderResponse out = base.withCourierName("ONLY-COURIER");

        assertThat(out.courierName()).isEqualTo("ONLY-COURIER");
        assertAllComponentsPreservedExcept(base, out, Set.of("courierName"));
    }

    /**
     * Reflects over every record component and asserts that each one not named in
     * {@code changed} is equal between {@code before} and {@code after}. This is
     * what catches a positional swap: a swapped wither moves a value into the
     * wrong component, so some "unchanged" component ends up different.
     */
    private static void assertAllComponentsPreservedExcept(
            OrderResponse before, OrderResponse after, Set<String> changed) {
        for (RecordComponent rc : OrderResponse.class.getRecordComponents()) {
            String name = rc.getName();
            if (changed.contains(name)) {
                continue;
            }
            Object b = invoke(rc, before);
            Object a = invoke(rc, after);
            assertThat(a)
                    .as("component '%s' must be preserved by the wither", name)
                    .isEqualTo(b);
        }
    }

    private static Object invoke(RecordComponent rc, OrderResponse target) {
        try {
            return rc.getAccessor().invoke(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read component " + rc.getName(), e);
        }
    }
}
