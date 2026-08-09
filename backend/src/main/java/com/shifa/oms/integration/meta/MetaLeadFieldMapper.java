package com.shifa.oms.integration.meta;

import com.shifa.oms.integration.shopify.MobileNumberNormalizer;
import com.shifa.oms.integration.meta.dto.MetaField;
import com.shifa.oms.lead.dto.CreateLeadRequest;
import com.shifa.oms.order.LeadSource;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Pure mapper from Meta Graph {@code field_data} to a {@link CreateLeadRequest}
 * (spec {@code meta-lead-sync}, Req 6, 7.3, 7.4).
 *
 * <p>Meta's standard field keys are {@code full_name} / {@code first_name} +
 * {@code last_name} / {@code email} / {@code phone_number}; forms can add arbitrary
 * custom questions. Standard keys map to the matching lead fields; everything else
 * (and an un-normalisable phone) is preserved in the note so nothing the customer
 * submitted is lost.
 *
 * <p>No Spring, no I/O — a total function over the field list.
 */
public final class MetaLeadFieldMapper {

    /** Placeholder name so capture succeeds when the form carried no name (Req 6.6). */
    static final String PLACEHOLDER_NAME = "Meta Lead";

    private static final int MAX_NAME = 120;
    private static final int MAX_NOTE = 1000;
    private static final int MAX_SOURCE_NOTE = 200;
    private static final int MAX_EMAIL = 150;

    private MetaLeadFieldMapper() {
        // Pure static helper.
    }

    /**
     * @param fields   the submitted answers from the Graph API
     * @param formName the Instant Form / campaign name, used as the source note (nullable)
     * @return a valid capture request (source FACEBOOK, NEW on capture)
     */
    public static CreateLeadRequest map(List<MetaField> fields, String formName) {
        String fullName = firstOf(fields, "full_name", "name");
        if (fullName == null) {
            String first = firstOf(fields, "first_name");
            String last = firstOf(fields, "last_name");
            fullName = joinNonBlank(first, last);
        }
        if (fullName == null || fullName.isBlank()) {
            fullName = PLACEHOLDER_NAME;
        }
        fullName = clamp(fullName.trim(), MAX_NAME);

        String rawPhone = firstOf(fields, "phone_number", "phone", "mobile", "mobile_number");
        Optional<String> mobile = MobileNumberNormalizer.normalize(rawPhone);

        String email = clamp(firstOf(fields, "email", "email_address"), MAX_EMAIL);

        String note = buildNote(fields, rawPhone, mobile.isPresent());
        String sourceNote = clamp(formName, MAX_SOURCE_NOTE);

        return new CreateLeadRequest(
                fullName,
                LeadSource.FACEBOOK,
                sourceNote,
                mobile.orElse(null),
                email,
                note,
                null);
    }

    /**
     * Builds the working note: custom-question answers plus, when the phone could not
     * be normalised, the original submitted value so it is not lost (Req 6.3, 6.5).
     */
    private static String buildNote(List<MetaField> fields, String rawPhone, boolean phoneMapped) {
        StringBuilder note = new StringBuilder();
        if (!phoneMapped && rawPhone != null && !rawPhone.isBlank()) {
            append(note, "Submitted phone: " + rawPhone.trim());
        }
        for (MetaField field : fields) {
            if (isStandardField(field.name())) {
                continue;
            }
            String value = field.value();
            if (value == null || value.isBlank()) {
                continue;
            }
            append(note, label(field.name()) + ": " + value.trim());
        }
        String text = note.toString().trim();
        return text.isEmpty() ? null : clamp(text, MAX_NOTE);
    }

    private static boolean isStandardField(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return switch (n) {
            case "full_name", "name", "first_name", "last_name",
                 "email", "email_address",
                 "phone_number", "phone", "mobile", "mobile_number" -> true;
            default -> false;
        };
    }

    /** Turns a raw field key like {@code company_name} into a readable label {@code Company name}. */
    private static String label(String name) {
        if (name == null || name.isBlank()) {
            return "Field";
        }
        String spaced = name.trim().replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String firstOf(List<MetaField> fields, String... names) {
        for (String wanted : names) {
            for (MetaField field : fields) {
                if (field.name() != null
                        && field.name().equalsIgnoreCase(wanted)
                        && field.value() != null
                        && !field.value().isBlank()) {
                    return field.value().trim();
                }
            }
        }
        return null;
    }

    private static String joinNonBlank(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.isBlank()) {
            sb.append(a.trim());
        }
        if (b != null && !b.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(b.trim());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static void append(StringBuilder note, String line) {
        if (note.length() > 0) {
            note.append('\n');
        }
        note.append(line);
    }

    private static String clamp(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
