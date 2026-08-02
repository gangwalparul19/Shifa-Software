package com.shifa.oms.integration;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 1: Event processing is idempotent.
 *
 * <p>For any Shopify order event or QuikShipX status event, and for any number of times
 * that event is delivered, re-drained or replayed, the Integration_Event_Store holds
 * exactly one record for that {@code (source, external event id)} and the recorded
 * outcome is the one the last processing produced.
 *
 * <p>Providers retry aggressively — Shopify especially — so this is the property that
 * stops a retried webhook from creating a second order.
 *
 * <p>Validates: Requirements 1.8, 2.6, 2.7, 6.9, 14.4, 14.5
 *
 * <p>Java 25 note: Mockito cannot mock concrete classes on this runtime, and the
 * repository is a Spring Data interface with a large inherited surface, so the double
 * is a reflective proxy implementing only the methods the store actually calls. Any
 * other call fails loudly rather than silently returning null.
 */
class IntegrationEventIdempotencePropertyTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-01T10:15:30Z"), ZoneOffset.UTC);

    @Property(tries = 500)
    void recordingTheSameEventManyTimesStoresItOnce(
            @ForAll IntegrationSource source,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 40) String externalEventId,
            @ForAll @IntRange(min = 1, max = 6) int deliveries) {

        Fake fake = new Fake();
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);

        int accepted = 0;
        for (int i = 0; i < deliveries; i++) {
            if (store.record(source, externalEventId, "orders/create", "{\"n\":" + i + "}").isPresent()) {
                accepted++;
            }
        }

        // Exactly one delivery is accepted; every repeat is a no-op.
        assertThat(accepted).isEqualTo(1);
        assertThat(fake.rows).hasSize(1);

        // The stored payload is the FIRST delivery's, not the last: a retry must not
        // rewrite the evidence of what originally arrived.
        IntegrationEvent stored = fake.rows.values().iterator().next();
        assertThat(stored.getRawPayload()).isEqualTo("{\"n\":0}");
        assertThat(stored.getOutcome()).isEqualTo(IntegrationOutcome.RECEIVED);
        assertThat(stored.getSource()).isEqualTo(source);
        assertThat(stored.getExternalEventId()).isEqualTo(externalEventId);
    }

    @Property(tries = 500)
    void distinctIdentifiersAreStoredSeparatelyPerSource(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 30) String idA,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 30) String idB) {

        Fake fake = new Fake();
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);

        // The same identifier under two different providers must NOT collide: the
        // unique key is scoped to the source on purpose.
        assertThat(store.record(IntegrationSource.SHOPIFY, idA, "t", "{}")).isPresent();
        assertThat(store.record(IntegrationSource.QUIKSHIPX, idA, "t", "{}")).isPresent();
        assertThat(fake.rows).hasSize(2);

        boolean distinct = !idA.equals(idB);
        assertThat(store.record(IntegrationSource.SHOPIFY, idB, "t", "{}").isPresent())
                .isEqualTo(distinct);
        assertThat(fake.rows).hasSize(distinct ? 3 : 2);
    }

    @Property(tries = 500)
    void markingAnOutcomeRepeatedlyConvergesOnTheLastVerdict(
            @ForAll IntegrationSource source,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 30) String externalEventId,
            @ForAll IntegrationOutcome first,
            @ForAll IntegrationOutcome second,
            @ForAll @IntRange(min = 1, max = 4) int repeats) {

        Fake fake = new Fake();
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);
        Long eventId = store.record(source, externalEventId, "t", "{}").orElseThrow().getId();

        for (int i = 0; i < repeats; i++) {
            store.markOutcome(eventId, first, "first");
        }
        for (int i = 0; i < repeats; i++) {
            store.markOutcome(eventId, second, "second");
        }

        // Re-marking is idempotent: still one row, holding the latest verdict. This is
        // what makes an admin replay safe to invoke twice.
        assertThat(fake.rows).hasSize(1);
        IntegrationEvent stored = fake.rows.values().iterator().next();
        assertThat(stored.getOutcome()).isEqualTo(second);
        assertThat(stored.getFailureReason()).isEqualTo("second");
    }

    @Property(tries = 300)
    void publicationFailuresForOneOrderUpdateASingleRow(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 3, max = 20) String orderCode,
            @ForAll @IntRange(min = 1, max = 6) int failures) {

        Fake fake = new Fake();
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);

        for (int attempt = 1; attempt <= failures; attempt++) {
            store.recordPublicationFailure(orderCode, 42L, IntegrationOutcome.PUBLICATION_FAILED,
                    "attempt " + attempt, attempt);
        }

        // Repeated failures for the same order must not flood the health console.
        assertThat(fake.rows).hasSize(1);
        IntegrationEvent stored = fake.rows.values().iterator().next();
        assertThat(stored.getOutcome()).isEqualTo(IntegrationOutcome.PUBLICATION_FAILED);
        assertThat(stored.getAttemptCount()).isEqualTo(failures);
        assertThat(stored.getOrderId()).isEqualTo(42L);
    }

    @Property(tries = 200)
    void aConcurrentDuplicateInsertIsTreatedAsADuplicateNotAnError(
            @ForAll IntegrationSource source,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 1, max = 30) String externalEventId) {

        // Simulates losing the race: the existence check passes, then the unique index
        // rejects the insert. The store must swallow that as a duplicate, because a
        // 500 here would make the provider retry forever.
        Fake fake = new Fake();
        fake.failNextSaveWithDuplicate = true;
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);

        Optional<IntegrationEvent> result = store.record(source, externalEventId, "t", "{}");

        assertThat(result).isEmpty();
    }

    // ------------------------------------------------------------------
    // In-memory repository double modelling UNIQUE(source, external_event_id).
    // ------------------------------------------------------------------

    private static final class Fake {

        private final Map<String, IntegrationEvent> rows = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();
        private boolean failNextSaveWithDuplicate = false;

        private static String key(IntegrationSource source, String externalEventId) {
            return source + "|" + externalEventId;
        }

        IntegrationEventRepository repository() {
            return (IntegrationEventRepository) Proxy.newProxyInstance(
                    IntegrationEventRepository.class.getClassLoader(),
                    new Class<?>[]{IntegrationEventRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "existsBySourceAndExternalEventId" ->
                                rows.containsKey(key((IntegrationSource) args[0], (String) args[1]));
                        case "findBySourceAndExternalEventId" ->
                                Optional.ofNullable(rows.get(key((IntegrationSource) args[0], (String) args[1])));
                        case "findById" -> rows.values().stream()
                                .filter(e -> args[0].equals(e.getId()))
                                .findFirst();
                        case "save", "saveAndFlush" -> persist((IntegrationEvent) args[0]);
                        // Any other call is a genuine gap in this double, not a pass.
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private IntegrationEvent persist(IntegrationEvent event) {
            if (failNextSaveWithDuplicate) {
                failNextSaveWithDuplicate = false;
                throw new DataIntegrityViolationException("ux_integration_events_ext");
            }
            if (event.getId() == null) {
                ReflectionTestUtils.setField(event, "id", sequence.incrementAndGet());
            }
            rows.put(key(event.getSource(), event.getExternalEventId()), event);
            return event;
        }
    }
}
