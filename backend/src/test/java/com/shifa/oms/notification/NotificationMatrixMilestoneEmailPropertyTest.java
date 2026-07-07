package com.shifa.oms.notification;

import com.shifa.oms.statemachine.OrderStatus;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for the milestone-email rule of the {@link NotificationMatrix}
 * (design §5.1, §Correctness Properties (17)).
 *
 * Feature: role-based-order-workflow, Property 17: Customer email is enqueued iff
 * the event is a key milestone.
 *
 * **Validates: Requirements 13.5, 13.6**
 *
 * <p>Pure and in-memory: exercises {@link NotificationMatrix} directly, with no
 * Spring, database, or Mockito mocks of concrete classes. Each {@code @Property}
 * runs the jqwik default of 1000 tries (≥ 100).
 */
class NotificationMatrixMilestoneEmailPropertyTest {

    private static final Set<OrderStatus> MILESTONES =
            Set.of(OrderStatus.APPROVED, OrderStatus.DISPATCHED, OrderStatus.DELIVERED);

    private final NotificationMatrix matrix = new NotificationMatrix();

    // Feature: role-based-order-workflow, Property 17: Customer email is enqueued iff the event is a key milestone
    // **Validates: Requirements 13.5, 13.6**
    @Property
    void customerEmailPresentIffKeyMilestone(@ForAll("statuses") OrderStatus event) {
        boolean hasCustomerEmail = matrix.specsFor(event).stream().anyMatch(s ->
                s.channel() == NotificationChannel.EMAIL
                        && s.recipient().kind() == NotificationRecipient.Kind.CUSTOMER);

        boolean isMilestone = MILESTONES.contains(event);

        // Email is in the matrix set iff the event is APPROVED / DISPATCHED / DELIVERED.
        assertThat(hasCustomerEmail).isEqualTo(isMilestone);
        // And the matrix's own predicate agrees.
        assertThat(matrix.hasCustomerEmail(event)).isEqualTo(isMilestone);
    }

    // Feature: role-based-order-workflow, Property 17: Customer email is enqueued iff the event is a key milestone
    // **Validates: Requirements 13.5, 13.6**
    @Property
    void noEmailChannelIsEverAddressedToStaff(@ForAll("statuses") OrderStatus event) {
        // Email only ever targets the customer — never a staff role/creator.
        boolean staffEmail = matrix.specsFor(event).stream().anyMatch(s ->
                s.channel() == NotificationChannel.EMAIL
                        && s.recipient().kind() != NotificationRecipient.Kind.CUSTOMER);
        assertThat(staffEmail).isFalse();
    }

    @Provide
    Arbitrary<OrderStatus> statuses() {
        return Arbitraries.of(OrderStatus.values());
    }
}
