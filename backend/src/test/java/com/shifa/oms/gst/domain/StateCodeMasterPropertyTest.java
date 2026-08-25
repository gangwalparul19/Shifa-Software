package com.shifa.oms.gst.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link StateCodeMaster} (GST filing compliance,
 * design Correctness Property 15).
 *
 * <p>Feature: gst-filing-compliance, Property 15 (state-code resolution and consistency).
 *
 * <p><b>Validates: Requirements 6.1, 6.2, 6.4.</b>
 *
 * <p>For any known Indian state/UT name (regardless of case or surrounding/internal
 * whitespace), {@link StateCodeMaster#resolve} returns its correct 2-digit GST code;
 * unknown or blank names resolve to {@link Optional#empty()}; and
 * {@link StateCodeMaster#resolveSellerCode} behaves identically to {@code resolve}
 * so the seller home-state code resolves through the same map (Req 6.4).
 */
class StateCodeMasterPropertyTest {

    private final StateCodeMaster master = new StateCodeMaster();

    /**
     * Known canonical state/UT name -> expected 2-digit GST code. A representative
     * spread across the statutory range plus common alternate spellings that the
     * master explicitly supports.
     */
    private static List<Tuple.Tuple2<String, String>> knownNames() {
        return List.of(
                Tuple.of("Jammu and Kashmir", "01"),
                Tuple.of("Jammu & Kashmir", "01"),
                Tuple.of("Himachal Pradesh", "02"),
                Tuple.of("Punjab", "03"),
                Tuple.of("Chandigarh", "04"),
                Tuple.of("Uttarakhand", "05"),
                Tuple.of("Uttaranchal", "05"),
                Tuple.of("Haryana", "06"),
                Tuple.of("Delhi", "07"),
                Tuple.of("NCT of Delhi", "07"),
                Tuple.of("Rajasthan", "08"),
                Tuple.of("Uttar Pradesh", "09"),
                Tuple.of("Bihar", "10"),
                Tuple.of("Sikkim", "11"),
                Tuple.of("Arunachal Pradesh", "12"),
                Tuple.of("Nagaland", "13"),
                Tuple.of("Manipur", "14"),
                Tuple.of("Mizoram", "15"),
                Tuple.of("Tripura", "16"),
                Tuple.of("Meghalaya", "17"),
                Tuple.of("Assam", "18"),
                Tuple.of("West Bengal", "19"),
                Tuple.of("Jharkhand", "20"),
                Tuple.of("Odisha", "21"),
                Tuple.of("Orissa", "21"),
                Tuple.of("Chhattisgarh", "22"),
                Tuple.of("Madhya Pradesh", "23"),
                Tuple.of("Gujarat", "24"),
                Tuple.of("Daman and Diu", "25"),
                Tuple.of("Dadra and Nagar Haveli and Daman and Diu", "26"),
                Tuple.of("Dadra and Nagar Haveli", "26"),
                Tuple.of("Maharashtra", "27"),
                Tuple.of("Karnataka", "29"),
                Tuple.of("Goa", "30"),
                Tuple.of("Lakshadweep", "31"),
                Tuple.of("Kerala", "32"),
                Tuple.of("Tamil Nadu", "33"),
                Tuple.of("Tamilnadu", "33"),
                Tuple.of("Puducherry", "34"),
                Tuple.of("Pondicherry", "34"),
                Tuple.of("Andaman and Nicobar Islands", "35"),
                Tuple.of("Telangana", "36"),
                Tuple.of("Andhra Pradesh", "37"),
                Tuple.of("Ladakh", "38"),
                Tuple.of("Other Territory", "97"));
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 15: State-code resolution and consistency
    // **Validates: Requirements 6.1, 6.2, 6.4**
    // For any known state/UT name, resolve returns its correct 2-digit code regardless of case
    // or surrounding/internal whitespace, and resolveSellerCode returns the same result.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 500)
    void resolvesKnownNamesCaseAndWhitespaceInsensitively(
            @ForAll("knownNameWithCode") Tuple.Tuple2<String, String> nameAndCode,
            @ForAll("caseMode") int caseMode,
            @ForAll("padding") String leading,
            @ForAll("padding") String trailing) {

        String canonical = nameAndCode.get1();
        String expectedCode = nameAndCode.get2();

        // Apply a case transform + surrounding whitespace to prove insensitivity (Req 6.1).
        String cased = switch (caseMode) {
            case 0 -> canonical.toLowerCase();
            case 1 -> canonical.toUpperCase();
            default -> canonical; // as-is
        };
        String input = leading + cased + trailing;

        Optional<String> resolved = master.resolve(input);
        assertThat(resolved)
                .as("resolve(\"%s\") should map to %s", input, expectedCode)
                .contains(expectedCode);

        // Every resolved code is a well-formed 2-digit GST state code (Req 6.2).
        assertThat(resolved.orElseThrow()).matches("\\d{2}");

        // Req 6.4: the seller home-state code resolves through the SAME map, so
        // resolveSellerCode is identical to resolve for every input.
        assertThat(master.resolveSellerCode(input))
                .as("resolveSellerCode must match resolve for \"%s\"", input)
                .isEqualTo(resolved);
    }

    // ---------------------------------------------------------------------------------------------
    // Feature: gst-filing-compliance, Property 15 (complement): unknown / blank names do not resolve
    // **Validates: Requirements 6.1** (and drives the unresolved-state flag, Req 6.3)
    // For any string that is not a known state/UT name (including null, empty, and blank),
    // resolve returns Optional.empty(), and resolveSellerCode agrees.
    // ---------------------------------------------------------------------------------------------
    @Property(tries = 300)
    void unknownOrBlankNamesResolveToEmpty(@ForAll("unknownName") String unknown) {
        Optional<String> resolved = master.resolve(unknown);
        assertThat(resolved)
                .as("resolve(\"%s\") should be empty for an unknown/blank name", unknown)
                .isEmpty();

        // resolveSellerCode behaves identically (Req 6.4).
        assertThat(master.resolveSellerCode(unknown)).isEqualTo(resolved);
    }

    // --- Generators ------------------------------------------------------------------------------

    @Provide
    Arbitrary<Tuple.Tuple2<String, String>> knownNameWithCode() {
        return Arbitraries.of(knownNames());
    }

    /** 0 = lower, 1 = upper, 2 = as-is. */
    @Provide
    Arbitrary<Integer> caseMode() {
        return Arbitraries.integers().between(0, 2);
    }

    /** Surrounding whitespace variants (including none) to prove trimming/collapsing. */
    @Provide
    Arbitrary<String> padding() {
        return Arbitraries.of("", " ", "  ", "\t", " \t ", "\n");
    }

    /**
     * Strings that are never a known state/UT name: blank variants, null, and arbitrary
     * non-state tokens (filtered to exclude any accidental known name, case-insensitively).
     */
    @Provide
    Arbitrary<String> unknownName() {
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t", "\n").injectNull(0.3);
        Arbitrary<String> tokens = Combinators.combine(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(12),
                        Arbitraries.strings().numeric().ofMaxLength(3))
                .as((word, digits) -> word + digits)
                .filter(s -> !isKnown(s));
        return Arbitraries.oneOf(blanks, tokens);
    }

    private static boolean isKnown(String candidate) {
        String norm = candidate.trim().replaceAll("\\s+", " ").toLowerCase();
        for (Tuple.Tuple2<String, String> t : knownNames()) {
            if (t.get1().toLowerCase().equals(norm)) {
                return true;
            }
        }
        return false;
    }
}
