package com.shifa.oms.gst.filing.domain;

import java.util.Collection;
import java.util.Optional;

/**
 * Pure, total versioning rules for a {@link ReturnPeriod}/{@link ReturnType}'s filing snapshots
 * (GST returns &amp; filing, Reqs 5.1, 5.6).
 *
 * <p>Filing snapshots form an append-only history: each time a return is filed (including after a
 * reopen) a new snapshot is stored with a monotonically incrementing version number starting at
 * {@code 1}, and the highest-version snapshot is always the current one served for a filed period.
 * This class encodes just that arithmetic and selection so it can be property-tested in isolation:
 *
 * <ul>
 *   <li>{@link #nextVersion(Collection)} — the next version to assign, {@code max(existing) + 1}, or
 *       {@code 1} when there are no existing versions (Req 5.6);</li>
 *   <li>{@link #current(Collection)} — the highest-version snapshot in a set, i.e. the current one
 *       (Req 5.1).</li>
 * </ul>
 *
 * <p>Pure and Spring-free; no JPA. The persistence-layer snapshot entity implements
 * {@link Versioned} so this logic applies to it unchanged.
 */
public final class SnapshotVersioning {

    private SnapshotVersioning() {
        // Utility holder; not instantiable.
    }

    /**
     * A minimal abstraction over anything carrying a monotonic snapshot {@link #version() version}
     * (from {@code 1}), so version selection stays generic and independent of the persistence model.
     */
    public interface Versioned {

        /** @return this snapshot's version number (monotonic from {@code 1}). */
        int version();
    }

    /**
     * The next version number to assign to a new snapshot: {@code max(existingVersions) + 1}, or
     * {@code 1} when the collection is empty (Req 5.6). Total — a {@code null} collection and
     * {@code null} elements are treated as absent, so this never throws.
     *
     * @param existingVersions the version numbers already assigned for the period/return type
     * @return the next monotonic version number ({@code ≥ 1})
     */
    public static int nextVersion(Collection<Integer> existingVersions) {
        if (existingVersions == null) {
            return 1;
        }
        boolean any = false;
        int max = Integer.MIN_VALUE;
        for (Integer version : existingVersions) {
            if (version != null) {
                any = true;
                if (version > max) {
                    max = version;
                }
            }
        }
        return any ? max + 1 : 1;
    }

    /**
     * The current snapshot — the one with the highest {@link Versioned#version() version} — from a
     * set of snapshots for a period/return type (Req 5.1). Total: returns {@link Optional#empty()}
     * when there are no snapshots (a not-yet-filed period).
     *
     * @param snapshots the snapshots to choose from
     * @param <T>       the concrete snapshot type
     * @return the highest-version snapshot, or {@link Optional#empty()} when none
     */
    public static <T extends Versioned> Optional<T> current(Collection<T> snapshots) {
        if (snapshots == null) {
            return Optional.empty();
        }
        T best = null;
        for (T snapshot : snapshots) {
            if (snapshot != null && (best == null || snapshot.version() > best.version())) {
                best = snapshot;
            }
        }
        return Optional.ofNullable(best);
    }
}
