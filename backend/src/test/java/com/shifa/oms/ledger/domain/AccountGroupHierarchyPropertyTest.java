package com.shifa.oms.ledger.domain;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link AccountGroupHierarchy} — the pure account-group hierarchy acyclic
 * check (General Ledger, design Correctness Property 2).
 *
 * <p>Feature: general-ledger-accounting, Property 2: The account-group hierarchy stays acyclic.
 *
 * <p>Property statement: for any account-group hierarchy, a request to create or re-parent a group
 * so that its parent is the group itself or any of its descendants is rejected, and every accepted
 * hierarchy contains no cycle.
 *
 * <p><b>Validates: Requirements 1.4</b>
 */
class AccountGroupHierarchyPropertyTest {

    // ---------------------------------------------------------------------------------------------
    // Feature: general-ledger-accounting, Property 2: The account-group hierarchy stays acyclic
    // **Validates: Requirements 1.4**
    //
    // The rule under test: attaching group g under proposed parent p creates a cycle IFF p is
    // non-null and p is g itself or one of g's descendants (equivalently, g is an ancestor of p).
    // Consequently every re-parent the check ACCEPTS leaves the hierarchy acyclic, and every
    // re-parent it REJECTS would have introduced a cycle.
    // ---------------------------------------------------------------------------------------------

    /**
     * The core detection rule is exact: {@code wouldCreateCycle} returns {@code true} exactly when
     * the proposed parent is the group itself or one of its descendants.
     */
    @Property(tries = 300)
    void cycleDetectionIsExact(@ForAll("acyclicHierarchies") Map<Long, Long> parentById,
                               @ForAll @IntRange(min = 0, max = 100_000) int groupSelector,
                               @ForAll @IntRange(min = 0, max = 100_000) int parentSelector) {
        List<Long> ids = sortedIds(parentById);
        long group = ids.get(groupSelector % ids.size());
        Long proposedParent = pickProposedParent(ids, parentSelector);

        // The group itself plus every group that has `group` on its ancestor chain (its descendants).
        Set<Long> selfAndDescendants = selfAndDescendants(group, parentById);
        boolean expectedCycle = proposedParent != null && selfAndDescendants.contains(proposedParent);

        assertThat(AccountGroupHierarchy.wouldCreateCycle(group, proposedParent, parentById))
                .as("would-create-cycle(%s under %s)", group, proposedParent)
                .isEqualTo(expectedCycle);
    }

    /**
     * Every attachment the check ACCEPTS leaves the hierarchy acyclic, and every attachment it
     * REJECTS would have made it cyclic — so {@code isAcyclic} of the re-parented hierarchy is the
     * exact negation of {@code wouldCreateCycle}.
     */
    @Property(tries = 300)
    void acceptedAttachmentsStayAcyclicAndRejectedOnesWouldCycle(
            @ForAll("acyclicHierarchies") Map<Long, Long> parentById,
            @ForAll @IntRange(min = 0, max = 100_000) int groupSelector,
            @ForAll @IntRange(min = 0, max = 100_000) int parentSelector) {
        List<Long> ids = sortedIds(parentById);
        long group = ids.get(groupSelector % ids.size());
        Long proposedParent = pickProposedParent(ids, parentSelector);

        boolean rejected = AccountGroupHierarchy.wouldCreateCycle(group, proposedParent, parentById);

        Map<Long, Long> reparented = new HashMap<>(parentById);
        reparented.put(group, proposedParent);

        // Accepted (not rejected) => acyclic; rejected => the applied re-parent forms a cycle.
        assertThat(AccountGroupHierarchy.isAcyclic(reparented))
                .as("acyclic after re-parenting %s under %s", group, proposedParent)
                .isEqualTo(!rejected);
    }

    /** Making a group its own parent is always rejected as a (self) cycle. */
    @Property(tries = 200)
    void selfParentIsAlwaysRejected(@ForAll("acyclicHierarchies") Map<Long, Long> parentById,
                                    @ForAll @IntRange(min = 0, max = 100_000) int groupSelector) {
        List<Long> ids = sortedIds(parentById);
        long group = ids.get(groupSelector % ids.size());

        assertThat(AccountGroupHierarchy.wouldCreateCycle(group, group, parentById)).isTrue();
    }

    /**
     * A well-formed generated hierarchy is always acyclic, and re-parenting any group to a root
     * (null parent) never introduces a cycle.
     */
    @Property(tries = 200)
    void generatedHierarchyIsAcyclicAndRootAttachmentNeverCycles(
            @ForAll("acyclicHierarchies") Map<Long, Long> parentById,
            @ForAll @IntRange(min = 0, max = 100_000) int groupSelector) {
        List<Long> ids = sortedIds(parentById);
        long group = ids.get(groupSelector % ids.size());

        assertThat(AccountGroupHierarchy.isAcyclic(parentById)).isTrue();
        assertThat(AccountGroupHierarchy.wouldCreateCycle(group, null, parentById)).isFalse();
    }

    // --- Helpers ---------------------------------------------------------------------------------

    private static List<Long> sortedIds(Map<Long, Long> parentById) {
        List<Long> ids = new ArrayList<>(parentById.keySet());
        Collections.sort(ids);
        return ids;
    }

    /**
     * Pick a proposed parent from the id space, reserving one selector slot for {@code null} (a root
     * attachment) so both the "attach under an existing group" and "attach as root" cases are
     * exercised.
     */
    private static Long pickProposedParent(List<Long> ids, int parentSelector) {
        int choice = parentSelector % (ids.size() + 1);
        return choice == ids.size() ? null : ids.get(choice);
    }

    /**
     * The set containing {@code group} and every group whose ancestor chain passes through
     * {@code group} (its descendants) — the exact set of proposed parents that must be rejected.
     */
    private static Set<Long> selfAndDescendants(long group, Map<Long, Long> parentById) {
        Set<Long> closure = new HashSet<>();
        closure.add(group);
        for (Long node : parentById.keySet()) {
            Set<Long> seen = new HashSet<>();
            Long current = node;
            while (current != null && seen.add(current)) {
                if (current == group) {
                    closure.add(node);
                    break;
                }
                current = parentById.get(current);
            }
        }
        return closure;
    }

    // --- Generators ------------------------------------------------------------------------------

    /**
     * Generate an acyclic account-group hierarchy as a {@code group id -> parent id} map. Groups are
     * numbered {@code 1..n}; each group's parent is chosen from a strictly smaller id (or is a root),
     * which structurally guarantees the generated hierarchy is acyclic while still producing varied
     * shapes (chains, bushy trees, multiple roots).
     */
    @Provide
    Arbitrary<Map<Long, Long>> acyclicHierarchies() {
        return Arbitraries.integers().between(1, 12).flatMap(n -> {
            List<Arbitrary<Long>> parentChoices = new ArrayList<>();
            for (int id = 1; id <= n; id++) {
                // 0 denotes a root (null parent); otherwise the parent is a strictly smaller id.
                parentChoices.add(Arbitraries.longs().between(0L, id - 1L));
            }
            return Combinators.combine(parentChoices).as(chosen -> {
                Map<Long, Long> map = new HashMap<>();
                for (int id = 1; id <= n; id++) {
                    long parent = chosen.get(id - 1);
                    map.put((long) id, parent == 0L ? null : parent);
                }
                return map;
            });
        });
    }
}
