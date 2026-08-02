package com.shifa.oms.integration.shopify;

import com.shifa.oms.integration.MalformedPayloadException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.NotBlank;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Feature: shopify-quikshipx-order-sync, Property 6: Shopify order mapping preserves every
 * supplied field, Property 7: contact-number normalisation is total and digit-exact, and
 * Property 8: SKU matching links exactly one product or none.
 *
 * <p>Validates: Requirements 3.1–3.7, 3.11, 8.4, 8.6, 8.7, 8.8
 */
class ShopifyMappingPropertyTest {

    // --- Property 7: contact-number normalisation ---------------------------

    @Property(tries = 1000)
    void normalisationIsEitherAbsentOrExactlyTenDigits(
            @ForAll @StringLength(max = 30) String raw) {

        MobileNumberNormalizer.normalize(raw).ifPresent(normalised -> {
            // customer_mobile is VARCHAR(10) NOT NULL, so anything else cannot be stored.
            assertThat(normalised).hasSize(10);
            assertThat(normalised).containsOnlyDigits();
        });
        // The NOT NULL column always gets a storable value.
        assertThat(MobileNumberNormalizer.normalizeOrEmpty(raw)).isNotNull();
    }

    @Property(tries = 1000)
    void aTenDigitCoreSurvivesAnyDecorationAndAnyPrefix(
            @ForAll @LongRange(min = 6_000_000_000L, max = 9_999_999_999L) long core,
            @ForAll @IntRange(min = 0, max = 3) int prefixKind,
            @ForAll @IntRange(min = 0, max = 3) int decorationKind) {

        String digits = Long.toString(core);
        String prefixed = switch (prefixKind) {
            case 1 -> "91" + digits;
            case 2 -> "0" + digits;
            case 3 -> "0091" + digits;
            default -> digits;
        };
        String decorated = switch (decorationKind) {
            case 1 -> "+" + prefixed;
            case 2 -> spread(prefixed, ' ');
            case 3 -> spread(prefixed, '-');
            default -> prefixed;
        };

        // However the buyer typed it, the stored number is the same national number.
        assertThat(MobileNumberNormalizer.normalize(decorated)).contains(digits);
    }

    @Test
    void aGenuineNumberBeginningNineOneIsNotMangled() {
        // "9198765432" is a valid 10-digit number that happens to start with the country
        // code. Peeling blindly would corrupt it into 8 digits and lose the customer.
        assertThat(MobileNumberNormalizer.normalize("9198765432")).contains("9198765432");
        // With the country code genuinely present it IS peeled.
        assertThat(MobileNumberNormalizer.normalize("919198765432")).contains("9198765432");
    }

    @Test
    void unusableContactNumbersYieldEmptyRatherThanATruncatedValue() {
        // A truncated number is worse than none: it looks dialable and is not.
        assertThat(MobileNumberNormalizer.normalize("12345")).isEmpty();
        assertThat(MobileNumberNormalizer.normalize("not a phone")).isEmpty();
        assertThat(MobileNumberNormalizer.normalize("")).isEmpty();
        assertThat(MobileNumberNormalizer.normalize(null)).isEmpty();
        assertThat(MobileNumberNormalizer.normalizeOrEmpty(null)).isEmpty();
    }

    // --- Property 8: SKU matching ------------------------------------------

    @Property(tries = 500)
    void aSkuMatchesOnlyWhenExactlyOneProductCarriesIt(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 2, max = 12) String sku,
            @ForAll @IntRange(min = 0, max = 3) int duplicates) {

        List<ShopifySkuMatcher.CatalogueEntry> catalogue = new ArrayList<>();
        for (int i = 0; i < duplicates; i++) {
            catalogue.add(new ShopifySkuMatcher.CatalogueEntry((long) (i + 1), sku));
        }
        Map<String, Long> index = ShopifySkuMatcher.index(catalogue);

        ShopifySkuMatcher.Match match = ShopifySkuMatcher.match(sku, index);

        switch (duplicates) {
            case 0 -> {
                assertThat(match.outcome()).isEqualTo(ShopifySkuMatcher.Outcome.NOT_FOUND);
                assertThat(match.needsReview()).isTrue();
            }
            case 1 -> {
                assertThat(match.outcome()).isEqualTo(ShopifySkuMatcher.Outcome.MATCHED);
                assertThat(match.productId()).isEqualTo(1L);
                assertThat(match.needsReview()).isFalse();
            }
            default -> {
                // Ambiguity is NOT resolved arbitrarily: linking either product would
                // misattribute revenue and stock invisibly.
                assertThat(match.outcome()).isEqualTo(ShopifySkuMatcher.Outcome.AMBIGUOUS);
                assertThat(match.productId()).isNull();
                assertThat(match.needsReview()).isTrue();
            }
        }
    }

    @Property(tries = 300)
    void skuComparisonIgnoresCaseAndSurroundingWhitespace(
            @ForAll @NotBlank @AlphaChars @StringLength(min = 2, max = 10) String sku) {

        Map<String, Long> index = ShopifySkuMatcher.index(
                List.of(new ShopifySkuMatcher.CatalogueEntry(42L, sku.toUpperCase())));

        // Hand-typed SKUs vary in case and padding; they are the same product.
        assertThat(ShopifySkuMatcher.matchedProductId("  " + sku.toLowerCase() + "  ", index))
                .contains(42L);
    }

    @Test
    void anAbsentSkuIsDistinguishedFromAnUnknownOne() {
        Map<String, Long> index = ShopifySkuMatcher.index(
                List.of(new ShopifySkuMatcher.CatalogueEntry(1L, "SHR-1")));

        // Different causes, so the review queue can say which happened.
        assertThat(ShopifySkuMatcher.match(null, index).outcome())
                .isEqualTo(ShopifySkuMatcher.Outcome.ABSENT);
        assertThat(ShopifySkuMatcher.match("   ", index).outcome())
                .isEqualTo(ShopifySkuMatcher.Outcome.ABSENT);
        assertThat(ShopifySkuMatcher.match("SHR-9", index).outcome())
                .isEqualTo(ShopifySkuMatcher.Outcome.NOT_FOUND);
    }

    // --- Property 6 / 14: payload round trip and tolerance -----------------

    @Property(tries = 500)
    void serializingThenParsingPreservesEverySuppliedField(
            @ForAll("orders") ShopifyOrderModel original) {

        ShopifyOrderModel reparsed = ShopifyOrderPayloadCodec.parse(
                ShopifyOrderPayloadCodec.serialize(original));

        assertThat(reparsed.shopifyOrderId()).isEqualTo(original.shopifyOrderId());
        assertThat(reparsed.shopifyOrderNumber()).isEqualTo(original.shopifyOrderNumber());
        assertThat(reparsed.customerName()).isEqualTo(original.customerName());
        assertThat(reparsed.contactNumber()).isEqualTo(original.contactNumber());
        assertThat(reparsed.email()).isEqualTo(original.email());
        assertThat(reparsed.address()).isEqualTo(original.address());
        // The total is carried verbatim, never recomputed from the lines (Req 3.6).
        assertThat(reparsed.totalPrice()).isEqualByComparingTo(original.totalPrice());
        assertThat(reparsed.lineItems()).hasSameSizeAs(original.lineItems());
        for (int i = 0; i < original.lineItems().size(); i++) {
            ShopifyOrderModel.LineItem expected = original.lineItems().get(i);
            ShopifyOrderModel.LineItem actual = reparsed.lineItems().get(i);
            assertThat(actual.sku()).isEqualTo(expected.sku());
            assertThat(actual.name()).isEqualTo(expected.name());
            assertThat(actual.quantity()).isEqualTo(expected.quantity());
            assertThat(actual.unitPrice()).isEqualByComparingTo(expected.unitPrice());
        }
    }

    @Test
    void unknownFieldsAreIgnoredSoAShopifyApiBumpDoesNotBreakIngestion() {
        String json = """
                {"id":"12345","name":"#1042","total_price":"999.00",
                 "some_new_field":"whatever","another":{"nested":true},
                 "shipping_address":{"address1":"12 MG Road","city":"Pune",
                   "province":"Maharashtra","zip":"411001","phone":"+91 98123 45678"},
                 "customer":{"first_name":"Asha","last_name":"Kumar","loyalty_tier":"gold"},
                 "line_items":[{"sku":"SHR-1","name":"Ashwagandha","quantity":2,
                   "price":"499.50","fulfillable_quantity":2}]}
                """;

        ShopifyOrderModel model = ShopifyOrderPayloadCodec.parse(json);

        assertThat(model.shopifyOrderId()).isEqualTo("12345");
        assertThat(model.customerName()).isEqualTo("Asha Kumar");
        assertThat(model.contactNumber()).isEqualTo("+91 98123 45678");
        assertThat(model.address().city()).isEqualTo("Pune");
        assertThat(model.lineItems()).hasSize(1);
        // Money read through BigDecimal, not a double, so 499.50 stays exact.
        assertThat(model.lineItems().get(0).unitPrice()).isEqualByComparingTo("499.50");
        assertThat(model.totalPrice()).isEqualByComparingTo("999.00");
        assertThat(model.totalsAgree()).isTrue();
    }

    @Test
    void onlyTheOrderIdIsRequired() {
        // Without an id there is no idempotency key, so a retry would duplicate the order.
        assertThatThrownBy(() -> ShopifyOrderPayloadCodec.parse("{\"name\":\"#1\"}"))
                .isInstanceOf(MalformedPayloadException.class)
                .hasMessageContaining("id");
        assertThatThrownBy(() -> ShopifyOrderPayloadCodec.parse("not json"))
                .isInstanceOf(MalformedPayloadException.class);
        assertThatThrownBy(() -> ShopifyOrderPayloadCodec.parse("[]"))
                .isInstanceOf(MalformedPayloadException.class);

        // Everything else degrades rather than failing: an order the store already took
        // must never be lost.
        ShopifyOrderModel bare = ShopifyOrderPayloadCodec.parse("{\"id\":\"9\"}");
        assertThat(bare.shopifyOrderId()).isEqualTo("9");
        assertThat(bare.lineItems()).isEmpty();
        assertThat(bare.address().isComplete()).isFalse();
        assertThat(bare.totalPrice()).isEqualByComparingTo("0");
    }

    @Test
    void theCustomerNameIsFoundWhereverShopifyPutIt() {
        // Only on the address (a guest checkout).
        assertThat(ShopifyOrderPayloadCodec.parse("""
                {"id":"1","shipping_address":{"first_name":"Ravi","last_name":"Sharma"}}
                """).customerName()).isEqualTo("Ravi Sharma");

        // Only as a single "name" field.
        assertThat(ShopifyOrderPayloadCodec.parse("""
                {"id":"1","shipping_address":{"name":"Ravi Sharma"}}
                """).customerName()).isEqualTo("Ravi Sharma");

        // Billing only, when there is no shipping address at all.
        assertThat(ShopifyOrderPayloadCodec.parse("""
                {"id":"1","billing_address":{"first_name":"Ravi","city":"Pune"}}
                """).customerName()).isEqualTo("Ravi");
    }

    @Test
    void aSecondAddressLineIsKeptRatherThanDropped() {
        ShopifyOrderModel model = ShopifyOrderPayloadCodec.parse("""
                {"id":"1","shipping_address":{"address1":"12 MG Road","address2":"Flat 4B",
                  "city":"Pune","province":"Maharashtra","zip":"411001"}}
                """);

        // Dropping address2 would lose the flat number and misdeliver the parcel.
        assertThat(model.address().addressLine()).isEqualTo("12 MG Road, Flat 4B");
        assertThat(model.address().isComplete()).isTrue();
    }

    @Test
    void aTotalThatDisagreesWithTheLinesIsDetectedButToleratedWithinARupee() {
        ShopifyOrderModel matching = model("1000.00", 2, "500.00");
        assertThat(matching.totalsAgree()).isTrue();

        // Shopify applies shipping, discounts and rounding Shifa does not reproduce, so a
        // rupee of slack avoids flagging every order.
        assertThat(model("1000.50", 2, "500.00").totalsAgree()).isTrue();
        assertThat(model("1200.00", 2, "500.00").totalsAgree()).isFalse();
    }

    // ------------------------------------------------------------------
    // Generators and fixtures
    // ------------------------------------------------------------------

    private static ShopifyOrderModel model(String total, int quantity, String unitPrice) {
        return new ShopifyOrderModel("1", "#1", "Asha", "9812345678", "a@b.com",
                new ShopifyOrderModel.Address("12 MG Road", "Pune", "Maharashtra", "411001", "India"),
                List.of(new ShopifyOrderModel.LineItem("SHR-1", "Item", quantity, new BigDecimal(unitPrice))),
                new BigDecimal(total), "INR", "paid");
    }

    private static String spread(String digits, char separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && i % 3 == 0) {
                sb.append(separator);
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    @Provide
    Arbitrary<ShopifyOrderModel> orders() {
        Arbitrary<Integer> ids = Arbitraries.integers().between(1, 99_999);
        Arbitrary<Integer> itemCounts = Arbitraries.integers().between(0, 4);
        return Combinators.combine(ids, itemCounts).as((id, count) -> {
            List<ShopifyOrderModel.LineItem> items = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                items.add(new ShopifyOrderModel.LineItem(
                        "SHR-" + i, "Item " + i, i, new BigDecimal("199.50")));
            }
            return new ShopifyOrderModel(
                    String.valueOf(id), "#" + id, "Asha Kumar", "+91 98123 45678",
                    "asha@example.com",
                    new ShopifyOrderModel.Address("12 MG Road", "Pune", "Maharashtra", "411001", "India"),
                    items, new BigDecimal("999.00"), "INR", "paid");
        });
    }
}
