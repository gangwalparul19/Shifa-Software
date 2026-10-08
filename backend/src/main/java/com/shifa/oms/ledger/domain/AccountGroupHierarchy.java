package com.shifa.oms.ledger.domain;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Pure acyclicity check for the Tally-style account-group hierarchy (General Ledger, Req 1.4).
 *
 * <p>Account groups form a tree: each group may nest under a single parent group. Creating a new
 * group under a parent, or re-parenting an existing group, must never make the hierarchy cyclic.
 * A cycle would arise precisely when the proposed parent is the group <em>itself</em> or any of the
 * group's <em>descendants</em> — equivalently, when the group is an ancestor of the proposed parent.
 *
 * <p>This class encodes that single rule as a pure function over the current parent-of mapping, so
 * it has no dependency on Spring or JPA and is fully unit- and property-testable. The
 * {@code ChartOfAccountsService} calls it before persisting a create/re-parent request and rejects
 * the request with a {@code common.ValidationException} when it would introduce a cycle.
 *
 * <p><strong>Validates Property 2 (the account-group hierarchy stays acyclic).</strong>
 */
public final class AccountGroupHierarchy {

    private AccountGroupHierarchy() {
    }

    /**
     * Determine whether attaching group {@code groupId} under {@code proposedParentId} would create a
     * cycle in the account-group hierarchy.
     *
     * <p>The check walks <em>up</em> the ancestor chain from the proposed parent using
     * {@code parentOf}: starting at the proposed parent and following each node's parent until a root
     * (a {@code null} parent) is reached. If the walk encounters {@code groupId} — either because the
     * proposed parent <em>is</em> the group (a self-parent) or because the group is an ancestor of the
     * proposed parent (i.e. the proposed parent is a descendant of the group) — the attachment would
     * close a cycle and this returns {@code true}.
     *
     * <p>A {@code null} {@code proposedParentId} means the group becomes a root and can never create a
     * cycle, so this returns {@code false}. The walk carries a visited set purely as a safety guard so
     * that a pre-existing cycle among the ancestors (which should never occur in well-formed data)
     * cannot cause an infinite loop; such a cycle that does not pass through {@code groupId} is
     * reported as {@code false} (it is not introduced by this request).
     *
     * @param groupId          the id of the group being created or re-parented
     * @param proposedParentId the id of the group it would be attached under, or {@code null} for a root
     * @param parentOf         a function from a group id to its current parent id, returning {@code null}
     *                         for a root group (or for an unknown id)
     * @return {@code true} if the attachment would make the proposed parent the group itself or a
     *         descendant of the group (a cycle); {@code false} otherwise
     * @throws NullPointerException if {@code parentOf} is {@code null}
     */
    public static boolean wouldCreateCycle(long groupId, Long proposedParentId, Function<Long, Long> parentOf) {
        Objects.requireNonNull(parentOf, "parentOf");
        Set<Long> visited = new HashSet<>();
        Long current = proposedParentId;
        while (current != null) {
            if (current.longValue() == groupId) {
                // Reached the group itself while climbing from the proposed parent:
                // the proposed parent is the group or one of its descendants -> cycle.
                return true;
            }
            if (!visited.add(current)) {
                // Pre-existing cycle among ancestors that does not involve groupId; stop safely.
                return false;
            }
            current = parentOf.apply(current);
        }
        return false;
    }

    /**
     * Convenience overload of {@link #wouldCreateCycle(long, Long, Function)} that reads parents from a
     * {@code group id -> parent id} map (a {@code null} or absent value denotes a root group).
     *
     * @param groupId          the id of the group being created or re-parented
     * @param proposedParentId the id of the group it would be attached under, or {@code null} for a root
     * @param parentById       the current mapping from a group id to its parent id
     * @return {@code true} if the attachment would create a cycle; {@code false} otherwise
     * @throws NullPointerException if {@code parentById} is {@code null}
     */
    public static boolean wouldCreateCycle(long groupId, Long proposedParentId, Map<Long, Long> parentById) {
        Objects.requireNonNull(parentById, "parentById");
        return wouldCreateCycle(groupId, proposedParentId, parentById::get);
    }

    /**
     * Determine whether an entire account-group hierarchy is acyclic — i.e. following the parent
     * chain from every group eventually reaches a root ({@code null} parent) without revisiting a
     * group. A well-formed Tally-style hierarchy is always acyclic; this is the whole-hierarchy
     * counterpart of {@link #wouldCreateCycle} and lets callers (and the Property 2 test) assert that
     * every <em>accepted</em> hierarchy contains no cycle.
     *
     * <p>A group whose parent id is absent from the map, or is {@code null}, is treated as a root.
     *
     * @param parentByGroupId the current mapping from a group id to its parent id (a {@code null} or
     *                        absent value denotes a root group)
     * @return {@code true} if no group lies on a cycle; {@code false} if any parent chain loops back
     *         on itself
     * @throws NullPointerException if {@code parentByGroupId} is {@code null}
     */
    public static boolean isAcyclic(Map<Long, Long> parentByGroupId) {
        Objects.requireNonNull(parentByGroupId, "parentByGroupId");
        for (Long start : parentByGroupId.keySet()) {
            if (start == null) {
                continue;
            }
            Set<Long> visited = new HashSet<>();
            visited.add(start);
            Long current = parentByGroupId.get(start);
            while (current != null) {
                if (!visited.add(current)) {
                    // Revisited a group while climbing -> this chain closes a cycle.
                    return false;
                }
                current = parentByGroupId.get(current);
            }
        }
        return true;
    }
}
