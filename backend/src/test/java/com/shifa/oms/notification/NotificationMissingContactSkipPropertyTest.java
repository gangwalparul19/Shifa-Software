package com.shifa.oms.notification;

import com.shifa.oms.order.domain.PaymentStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test that a missing customer contact skips only that customer
 * channel (design §5.2, §Correctness Properties (18)).
 *
 * Feature: role-based-order-workflow, Property 18: Missing contact info skips only
 * that customer channel.
 *
 * **Validates: Requirements 7.5**
 *
 * <p>In-memory: the real {@link NotificationDispatcher} + publishers wired over
 * mocked repository interfaces (no concrete-class mocks). For an order missing its
 * mobile, the enqueued set excludes only the WhatsApp customer spec (and the skip
 * is recorded); symmetrically for a missing email. Every other notification the
 * matrix specifies is still enqueued. Each {@code @Property} runs the jqwik
 * default of 1000 tries (≥ 100).
 */
class NotificationMissingContactSkipPropertyTest {

    private final NotificationMatrix matrix = new NotificationMatrix();

    // Feature: role-based-order-workflow, Property 18: Missing contact info skips only that customer channel
    // **Validates: Requirements 7.5**
    @Property
    void missingMobileSkipsOnlyWhatsApp(@ForAll("statuses") OrderStatus event) {
        NotificationMatrixEnqueuePropertyTest.Recorder recorder =
                new NotificationMatrixEnqueuePropertyTest.Recorder();
        NotificationDispatcher dispatcher = recorder.dispatcher(matrix);

        // Mobile absent, email present, salesperson present.
        NotificationTarget target = new NotificationTarget(
                42L, "SHR-000042", "  ", "asha@example.com", "Asha",
                7L, PaymentStatus.COD, new BigDecimal("240.00"));

        NotificationDispatcher.Result result = dispatcher.dispatch(target, event, null);

        NotificationSpec whatsAppCustomer = NotificationSpec.of(
                NotificationChannel.WHATSAPP, NotificationRecipient.customer());
        Set<NotificationSpec> expected = matrix.specsFor(event);
        boolean matrixHasWhatsApp = expected.contains(whatsAppCustomer);

        // WhatsApp is skipped iff the matrix specified it; everything else enqueues.
        assertThat(result.enqueued()).isEqualTo(without(expected, whatsAppCustomer));
        assertThat(result.skipped()).isEqualTo(
                matrixHasWhatsApp ? Set.of(whatsAppCustomer) : Set.of());
        // The email spec (when present) still enqueued.
        assertThat(recorder.reconstructEnqueuedSpecs()).isEqualTo(without(expected, whatsAppCustomer));
    }

    // Feature: role-based-order-workflow, Property 18: Missing contact info skips only that customer channel
    // **Validates: Requirements 7.5**
    @Property
    void missingEmailSkipsOnlyEmail(@ForAll("statuses") OrderStatus event) {
        NotificationMatrixEnqueuePropertyTest.Recorder recorder =
                new NotificationMatrixEnqueuePropertyTest.Recorder();
        NotificationDispatcher dispatcher = recorder.dispatcher(matrix);

        // Email absent, mobile present, salesperson present.
        NotificationTarget target = new NotificationTarget(
                42L, "SHR-000042", "9812345678", null, "Asha",
                7L, PaymentStatus.COD, new BigDecimal("240.00"));

        NotificationDispatcher.Result result = dispatcher.dispatch(target, event, null);

        NotificationSpec emailCustomer = NotificationSpec.of(
                NotificationChannel.EMAIL, NotificationRecipient.customer());
        Set<NotificationSpec> expected = matrix.specsFor(event);
        boolean matrixHasEmail = expected.contains(emailCustomer);

        // Email is skipped iff the matrix specified it (a key milestone); rest enqueue.
        assertThat(result.enqueued()).isEqualTo(without(expected, emailCustomer));
        assertThat(result.skipped()).isEqualTo(
                matrixHasEmail ? Set.of(emailCustomer) : Set.of());
        assertThat(recorder.reconstructEnqueuedSpecs()).isEqualTo(without(expected, emailCustomer));
    }

    private static Set<NotificationSpec> without(Set<NotificationSpec> set, NotificationSpec exclude) {
        return set.stream().filter(s -> !s.equals(exclude)).collect(Collectors.toSet());
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }
}
