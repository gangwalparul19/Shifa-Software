package com.shifa.oms.order;

import com.shifa.oms.common.ValidationException;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Property-based test for lead-source validation on order entry (design
 * §Correctness Properties (8), §3.1, §6.1).
 *
 * Feature: role-based-order-workflow, Property 8: Lead source is validated
 * against the defined set.
 *
 * **Validates: Requirements 4.1, 4.2, 4.5**
 *
 * <p>For any raw lead-source name, {@link OrderCreationValidator#requireLeadSource(String)}
 * resolves it <em>iff</em> the name is exactly a member of the defined
 * {@link LeadSource} set (blank/{@code null}/unknown are rejected with a 400
 * {@link ValidationException}). Independently, the optional {@code leadSourceNote}
 * (only meaningful for {@link LeadSource#OTHER}) is accepted <em>iff</em> it is
 * at most {@value OrderCreationValidator#MAX_LEAD_SOURCE_NOTE_LENGTH} characters.
 *
 * <p>Pure, in-memory logic exercised directly; no Spring, no persistence, and no
 * mocks of any kind. Each {@code @Property} runs the jqwik default of 1000 tries
 * (≥ 100).
 */
class LeadSourceValidationPropertyTest {

    private static final Set<String> DEFINED_NAMES = Arrays.stream(LeadSource.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    // Feature: role-based-order-workflow, Property 8: Lead source is validated against the defined set
    // **Validates: Requirements 4.1, 4.2**
    @Property
    void leadSourceIsResolvedExactlyForDefinedNames(@ForAll("leadSourceNames") String rawName) {
        boolean inSet = rawName != null && DEFINED_NAMES.contains(rawName);

        if (inSet) {
            LeadSource resolved = OrderCreationValidator.requireLeadSource(rawName);
            assertThat(resolved).isNotNull();
            assertThat(resolved.name()).isEqualTo(rawName);
        } else {
            assertThatThrownBy(() -> OrderCreationValidator.requireLeadSource(rawName))
                    .isInstanceOf(ValidationException.class);
        }
    }

    // Feature: role-based-order-workflow, Property 8: Lead source is validated against the defined set
    // **Validates: Requirements 4.5**
    @Property
    void otherNoteIsAcceptedExactlyWhenWithinBound(@ForAll("notes") String note) {
        int max = OrderCreationValidator.MAX_LEAD_SOURCE_NOTE_LENGTH;
        boolean withinBound = note == null || note.length() <= max;

        Throwable thrown = catchThrowable(
                () -> OrderCreationValidator.validateLeadSourceNote(note));

        if (withinBound) {
            assertThat(thrown).isNull();
        } else {
            assertThat(thrown).isInstanceOf(ValidationException.class);
        }
    }

    // --- Generators ---------------------------------------------------------

    @Provide
    Arbitrary<String> leadSourceNames() {
        // Exact defined names, near-misses (wrong case / decorated), and arbitrary
        // junk — plus null — so both the in-set and out-of-set branches are hit.
        Arbitrary<String> defined = Arbitraries.of(LeadSource.values()).map(Enum::name);
        Arbitrary<String> nearMisses = Arbitraries.of(LeadSource.values())
                .map(s -> s.name().toLowerCase() + "x");
        Arbitrary<String> junk = Arbitraries.strings()
                .withCharRange('A', 'Z').ofMinLength(0).ofMaxLength(12);
        Arbitrary<String> blanks = Arbitraries.of("", " ", "   ", "\t");
        return Arbitraries.oneOf(defined, nearMisses, junk, blanks).injectNull(0.1);
    }

    @Provide
    Arbitrary<String> notes() {
        // Lengths straddling the 200-char bound (0..250), plus null.
        return Arbitraries.strings()
                .withCharRange('a', 'z').ofMinLength(0).ofMaxLength(250)
                .injectNull(0.1);
    }
}
