package com.shifa.oms.gst.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-code, immutable reference map of Indian state / union-territory names to
 * their 2-digit GST state codes (GST Filing Compliance, Req 6).
 *
 * <p>Chosen over a DB table because the state-code list is a static, rarely
 * changing statutory reference: no migration, no seed drift, and it is trivially
 * unit-testable. If the list ever needs runtime editing it can migrate to a table
 * behind this same {@code resolve} interface.
 *
 * <p>{@link #resolve(String)} is case- and whitespace-insensitive: the seed state
 * names in {@code delivery_states} (e.g. {@code "Tamil Nadu"}, {@code "Dadra and
 * Nagar Haveli and Daman and Diu"}) all resolve, as do common alternate spellings
 * (e.g. {@code "Orissa"}, {@code "Pondicherry"}). The seller's home state resolves
 * through the same map so intra- vs inter-state (CGST/SGST vs IGST) classification
 * stays consistent with {@link GstEngine#classify} (Req 6.4).
 *
 * <p>No Spring, no persistence — a pure reference type.
 */
public final class StateCodeMaster {

    /** Normalized state name -> 2-digit GST state code. Insertion order preserved for clarity. */
    private static final Map<String, String> BY_NAME;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        put(m, "01", "Jammu and Kashmir", "Jammu & Kashmir", "J&K");
        put(m, "02", "Himachal Pradesh");
        put(m, "03", "Punjab");
        put(m, "04", "Chandigarh");
        put(m, "05", "Uttarakhand", "Uttaranchal");
        put(m, "06", "Haryana");
        put(m, "07", "Delhi", "NCT of Delhi", "New Delhi");
        put(m, "08", "Rajasthan");
        put(m, "09", "Uttar Pradesh");
        put(m, "10", "Bihar");
        put(m, "11", "Sikkim");
        put(m, "12", "Arunachal Pradesh");
        put(m, "13", "Nagaland");
        put(m, "14", "Manipur");
        put(m, "15", "Mizoram");
        put(m, "16", "Tripura");
        put(m, "17", "Meghalaya");
        put(m, "18", "Assam");
        put(m, "19", "West Bengal");
        put(m, "20", "Jharkhand");
        put(m, "21", "Odisha", "Orissa");
        put(m, "22", "Chhattisgarh", "Chattisgarh");
        put(m, "23", "Madhya Pradesh");
        put(m, "24", "Gujarat");
        put(m, "25", "Daman and Diu", "Daman & Diu");
        // 26 is the merged UT (Dadra and Nagar Haveli and Daman and Diu); the legacy
        // standalone "Dadra and Nagar Haveli" also historically carried code 26.
        put(m, "26", "Dadra and Nagar Haveli and Daman and Diu",
                "Dadra and Nagar Haveli", "Dadra & Nagar Haveli");
        put(m, "27", "Maharashtra");
        put(m, "29", "Karnataka");
        put(m, "30", "Goa");
        put(m, "31", "Lakshadweep");
        put(m, "32", "Kerala");
        put(m, "33", "Tamil Nadu", "Tamilnadu");
        put(m, "34", "Puducherry", "Pondicherry");
        put(m, "35", "Andaman and Nicobar Islands", "Andaman & Nicobar Islands", "Andaman and Nicobar");
        put(m, "36", "Telangana");
        put(m, "37", "Andhra Pradesh");
        put(m, "38", "Ladakh");
        put(m, "97", "Other Territory");
        BY_NAME = Map.copyOf(m);
    }

    private static void put(Map<String, String> m, String code, String... names) {
        for (String name : names) {
            m.put(normalize(name), code);
        }
    }

    /**
     * Resolve a state / UT name (case- and whitespace-insensitive) to its 2-digit
     * GST state code (Req 6.1). Returns {@link Optional#empty()} when the name is
     * null, blank, or unknown — which drives the unresolved-state flag (Req 6.3).
     */
    public Optional<String> resolve(String stateName) {
        if (stateName == null || stateName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(BY_NAME.get(normalize(stateName)));
    }

    /**
     * Resolve the seller's home state code through the same map (Req 6.4), so that
     * CGST/SGST-vs-IGST classification agrees exactly when the seller and order
     * codes are equal.
     */
    public Optional<String> resolveSellerCode(String sellerState) {
        return resolve(sellerState);
    }

    /** Lowercase, trim, and collapse internal whitespace runs to a single space. */
    private static String normalize(String name) {
        return name.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
