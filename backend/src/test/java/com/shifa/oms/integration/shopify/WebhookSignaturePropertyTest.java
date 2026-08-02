package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.IntegrationEvent;
import com.shifa.oms.integration.IntegrationEventRepository;
import com.shifa.oms.integration.IntegrationEventStore;
import com.shifa.oms.integration.IntegrationOutcome;
import com.shifa.oms.integration.IntegrationSource;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: shopify-quikshipx-order-sync, Property 2: valid signatures verify and are
 * recorded, and Property 3: any invalid or absent signature is rejected with no
 * persistence.
 *
 * <p>The webhook is the only unauthenticated write path into the OMS, so these two
 * properties are the whole of its access control. Property 3 in particular is why the
 * verifier is checked <em>before</em> anything is written: an attacker who can persist a
 * payload has already achieved something, even if no order is created.
 *
 * <p>Validates: Requirements 2.2, 2.3, 2.4
 */
class WebhookSignaturePropertyTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-08-01T10:15:30Z"), ZoneOffset.UTC);

    // --- Property 2: a correct signature verifies and is recorded exactly once ---

    @Property(tries = 500)
    void aSignatureComputedWithTheConfiguredSecretVerifies(
            @ForAll @Size(min = 1, max = 400) byte[] body,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 40) String secret) {

        ShopifyWebhookVerifier verifier = verifierWith(secret);

        assertThat(verifier.isValid(body, ShopifyWebhookVerifier.sign(body, secret))).isTrue();
    }

    @Property(tries = 300)
    void anAcceptedDeliveryYieldsExactlyOneReceivedRecordHoldingTheBodyVerbatim(
            @ForAll @Size(min = 1, max = 200) byte[] body,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 40) String secret,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 4, max = 20) String webhookId,
            @ForAll @IntRange(min = 1, max = 4) int deliveries) {

        ShopifyWebhookVerifier verifier = verifierWith(secret);
        Fake fake = new Fake();
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);
        String payload = new String(body, StandardCharsets.UTF_8);
        String signature = ShopifyWebhookVerifier.sign(body, secret);

        for (int i = 0; i < deliveries; i++) {
            if (verifier.isValid(body, signature)) {
                store.record(IntegrationSource.SHOPIFY, webhookId, "orders/create", payload);
            }
        }

        // Shopify retries aggressively, so a genuine repeat must not multiply the rows.
        assertThat(fake.rows).hasSize(1);
        IntegrationEvent stored = fake.rows.values().iterator().next();
        assertThat(stored.getOutcome()).isEqualTo(IntegrationOutcome.RECEIVED);
        // Byte-equal: the HMAC covers exactly these bytes, so replay must reproduce them.
        assertThat(stored.getRawPayload().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
        assertThat(stored.getSource()).isEqualTo(IntegrationSource.SHOPIFY);
    }

    // --- Property 3: anything else is rejected, and nothing is written -----------

    @Property(tries = 1000)
    void anAbsentBlankOrAlteredSignatureIsRejectedAndPersistsNothing(
            @ForAll @Size(min = 1, max = 200) byte[] body,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 40) String secret,
            @ForAll @IntRange(min = 0, max = 4) int corruption) {

        ShopifyWebhookVerifier verifier = verifierWith(secret);
        String correct = ShopifyWebhookVerifier.sign(body, secret);
        String signature = switch (corruption) {
            case 0 -> null;
            case 1 -> "";
            case 2 -> "   ";
            case 3 -> flipLastCharacter(correct);
            default -> correct.substring(0, correct.length() - 1);
        };

        Fake fake = new Fake();
        IntegrationEventStore store = new IntegrationEventStore(fake.repository(), FIXED);

        boolean valid = verifier.isValid(body, signature);
        if (valid) {
            store.record(IntegrationSource.SHOPIFY, "id", "orders/create", "{}");
        }

        assertThat(valid).isFalse();
        // Nothing is persisted for a rejected delivery — not even the payload (Req 2.3).
        assertThat(fake.rows).isEmpty();
    }

    @Property(tries = 300)
    void aSignatureFromADifferentSecretDoesNotVerify(
            @ForAll @Size(min = 1, max = 200) byte[] body,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 30) String configured,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 30) String other) {

        ShopifyWebhookVerifier verifier = verifierWith(configured);

        boolean sameSecret = configured.equals(other);
        assertThat(verifier.isValid(body, ShopifyWebhookVerifier.sign(body, other)))
                .isEqualTo(sameSecret);
    }

    @Property(tries = 300)
    void aSignatureOverDifferentBytesDoesNotVerify(
            @ForAll @Size(min = 1, max = 100) byte[] body,
            @ForAll @NotBlank @AlphaChars @StringLength(min = 8, max = 30) String secret) {

        ShopifyWebhookVerifier verifier = verifierWith(secret);
        byte[] tampered = new byte[body.length + 1];
        System.arraycopy(body, 0, tampered, 0, body.length);
        tampered[body.length] = ' ';

        // Even appending a single space — the kind of change re-serialising JSON would
        // make — invalidates the signature. That is why the handler takes byte[].
        assertThat(verifier.isValid(tampered, ShopifyWebhookVerifier.sign(body, secret))).isFalse();
    }

    @Test
    void aBlankConfiguredSecretRejectsEveryDelivery() {
        byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);

        // Fails CLOSED, unlike the courier verifier. Accepting unsigned deliveries here
        // would make order creation an open endpoint for anyone who can POST JSON.
        for (String secret : new String[]{null, "", "   "}) {
            ShopifyWebhookVerifier verifier = verifierWith(secret);
            assertThat(verifier.isValid(body, "anything")).isFalse();
            assertThat(verifier.isValid(body, ShopifyWebhookVerifier.sign(body, "s"))).isFalse();
        }
    }

    @Test
    void theSignatureIsBase64NotHex() {
        byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
        String signature = ShopifyWebhookVerifier.sign(body, "secret");

        // Shopify sends base64 in X-Shopify-Hmac-Sha256. A hex digest would be 64 chars
        // of [0-9a-f] and would reject every genuine delivery.
        assertThat(signature).hasSize(44).endsWith("=");
        assertThat(signature).doesNotMatch("^[0-9a-f]{64}$");
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static ShopifyWebhookVerifier verifierWith(String secret) {
        return new ShopifyWebhookVerifier(new ShopifyProperties(
                Boolean.TRUE, secret, "shifa.myshopify.com",
                null, null, null, null, null));
    }

    private static String flipLastCharacter(String signature) {
        char last = signature.charAt(signature.length() - 1);
        char replacement = last == 'A' ? 'B' : 'A';
        return signature.substring(0, signature.length() - 1) + replacement;
    }

    /** In-memory {@code integration_events} modelling UNIQUE(source, external_event_id). */
    private static final class Fake {

        private final Map<String, IntegrationEvent> rows = new LinkedHashMap<>();
        private final AtomicLong sequence = new AtomicLong();

        IntegrationEventRepository repository() {
            return (IntegrationEventRepository) Proxy.newProxyInstance(
                    IntegrationEventRepository.class.getClassLoader(),
                    new Class<?>[]{IntegrationEventRepository.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "existsBySourceAndExternalEventId" ->
                                rows.containsKey(args[0] + "|" + args[1]);
                        case "findBySourceAndExternalEventId" ->
                                Optional.ofNullable(rows.get(args[0] + "|" + args[1]));
                        case "findById" -> rows.values().stream()
                                .filter(e -> args[0].equals(e.getId())).findFirst();
                        case "save", "saveAndFlush" -> persist((IntegrationEvent) args[0]);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }

        private IntegrationEvent persist(IntegrationEvent event) {
            if (event.getId() == null) {
                ReflectionTestUtils.setField(event, "id", sequence.incrementAndGet());
            }
            rows.put(event.getSource() + "|" + event.getExternalEventId(), event);
            return event;
        }
    }
}
