package com.shifa.oms.ledger.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link VoucherType} strict parsing (General Ledger, design Correctness
 * Property 11).
 *
 * <p>Feature: general-ledger-accounting, Property 11: Voucher type parsing round-trips.
 *
 * <p><b>Validates: Requirements 7.1, 7.2, 7.3</b>
 */
class VoucherTypePropertyTest {

    /** The eight supported voucher-type names (Req 7.1). */
    private static final List<String> SUPPORTED_NAMES = Arrays.stream(VoucherType.values())
            .map(Enum::name)
            .toList();

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 11: Voucher type parsing round-trips
    // **Validates: Requirements 7.1, 7.2, 7.3**
    // For each of the eight supported type names, fromName returns the matching type and name()
    // round-trips; for any string that is not a supported type name (including null, blank, and
    // random text), fromName returns Optional.empty().
    // ---------------------------------------------------------------------------------------------

    /** For every supported name, fromName resolves to the matching type and name() round-trips. */
    @Property(tries = 200)
    void supportedNamesRoundTrip(@ForAll("supportedNames") String name) {
        Optional<VoucherType> parsed = VoucherType.fromName(name);

        assertThat(parsed).isPresent();
        // The parsed type's canonical name is exactly the input name (round-trip).
        assertThat(parsed.get().name()).isEqualTo(name);
        // And re-parsing that canonical name yields the same type.
        assertThat(VoucherType.fromName(parsed.get().name())).contains(parsed.get());
    }

    /** Every enum value's own name() parses back to itself (exhaustive round-trip guarantee). */
    @Property(tries = 200)
    void everyTypeNameParsesBackToItself(@ForAll VoucherType type) {
        assertThat(VoucherType.fromName(type.name())).contains(type);
    }

    /** Any string that is not one of the eight supported names yields Optional.empty(). */
    @Property(tries = 300)
    void unsupportedNamesAreRejected(@ForAll("nonSupportedStrings") String name) {
        assertThat(VoucherType.fromName(name)).isEmpty();
    }

    /** null, blank, and whitespace-only inputs are always rejected. */
    @Property(tries = 100)
    void nullBlankAndWhitespaceAreRejected(@ForAll("blankStrings") String blank) {
        assertThat(VoucherType.fromName(blank)).isEmpty();
    }

    // --- Generators ------------------------------------------------------------------------------

    /** The eight canonical supported voucher-type names. */
    @Provide
    Arbitrary<String> supportedNames() {
        return Arbitraries.of(SUPPORTED_NAMES);
    }

    /** null plus blank/whitespace-only strings, none of which name a supported type. */
    @Provide
    Arbitrary<String> blankStrings() {
        return Arbitraries.of("", " ", "   ", "\t", "\n", "  \t \n ").injectNull(0.3);
    }

    /**
     * Arbitrary strings that are never one of the eight supported names: random text, casing/spacing
     * variants of real names, near-misses, plus null and blank values.
     */
    @Provide
    Arbitrary<String> nonSupportedStrings() {
        Arbitrary<String> randomText = Arbitraries.strings()
                .withCharRange('a', 'z')
                .withCharRange('A', 'Z')
                .withChars(' ', '_', '-', '0', '9')
                .ofMinLength(0)
                .ofMaxLength(20)
                .filter(s -> !SUPPORTED_NAMES.contains(s));

        // Deliberate near-misses: lower-case, padded-internally, and unsupported labels.
        Arbitrary<String> nearMisses = Arbitraries.of(
                "journal", "Journal", "PAYMENTS", "receipt ", " CONTRA", "SALE", "PURCHASES",
                "DEBIT NOTE", "CREDIT-NOTE", "debit_note", "UNKNOWN", "VOUCHER", "0", "NONE");

        return Arbitraries.oneOf(randomText, nearMisses).injectNull(0.1);
    }
}
