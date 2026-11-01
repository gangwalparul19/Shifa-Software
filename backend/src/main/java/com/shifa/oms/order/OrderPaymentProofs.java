package com.shifa.oms.order;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Small pure helpers for an order's payment-proof (screenshot) keys: building the
 * ordered, de-duplicated key list from a request and hashing proof bytes for
 * duplicate detection. Kept out of {@link OrderService}, which only has to decide
 * what to do with the result.
 */
final class OrderPaymentProofs {

    private OrderPaymentProofs() {
    }

    /**
     * The ordered, de-duplicated set of proof keys: the legacy single
     * {@code primaryKey} first (so it stays the primary proof), then any
     * {@code extraKeys}. Null/blank entries are dropped and keys are trimmed. An
     * order with no proofs yields an empty list.
     */
    static List<String> keysOf(String primaryKey, List<String> extraKeys) {
        List<String> keys = new ArrayList<>();
        add(keys, primaryKey);
        if (extraKeys != null) {
            for (String extra : extraKeys) {
                add(keys, extra);
            }
        }
        return keys;
    }

    /** The primary (first) proof of an ordered proof set, or null when there are none. */
    static String primary(List<String> keys) {
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** SHA-256 of the bytes as lowercase hex, or null for null input. */
    static String sha256Hex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return null; // SHA-256 is always available on a standard JVM.
        }
    }

    /** Appends a trimmed, non-blank, not-already-present key. */
    private static void add(List<String> keys, String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        if (!keys.contains(trimmed)) {
            keys.add(trimmed);
        }
    }
}
