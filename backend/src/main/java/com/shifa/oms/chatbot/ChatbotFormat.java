package com.shifa.oms.chatbot;

import com.shifa.oms.order.OrderStatusGroup;
import com.shifa.oms.order.PaymentVerificationStatus;
import com.shifa.oms.order.domain.PaymentStatus;
import com.shifa.oms.statemachine.OrderStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Display formatting for chatbot answers (Indian digit grouping, plurals, lists). */
final class ChatbotFormat {

    private static final String RUPEE = "\u20B9";

    private ChatbotFormat() {
    }

    /** "₹1,23,456" for whole rupees, "₹1,234.50" when there are paise. */
    static String inr(BigDecimal amount) {
        BigDecimal v = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        boolean negative = v.signum() < 0;
        String plain = v.abs().toPlainString();
        int dot = plain.indexOf('.');
        String whole = plain.substring(0, dot);
        String paise = plain.substring(dot + 1);
        return (negative ? "-" : "") + RUPEE + group(whole) + ("00".equals(paise) ? "" : "." + paise);
    }

    /** A whole number with Indian grouping ("1,23,456"). */
    static String count(long value) {
        return (value < 0 ? "-" : "") + group(Long.toString(Math.abs(value)));
    }

    /** "1 order" / "5 orders". */
    static String plural(long n, String singular) {
        return count(n) + " " + singular + (n == 1 ? "" : "s");
    }

    static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "A", "A and B", "A, B and C". */
    static String sentenceList(List<String> items) {
        if (items.isEmpty()) {
            return "";
        }
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.get(items.size() - 1);
    }

    /** "by Sameer Khan from Shopify": who and which channel a question was narrowed to (either may be empty). */
    static String filterText(com.shifa.oms.chatbot.domain.ParsedQuestion q, boolean includeChannel,
                             boolean includePeople) {
        String who = "";
        if (includePeople && !q.people().isEmpty()) {
            List<String> names = q.people().stream().map(com.shifa.oms.chatbot.domain.PersonRef::fullName).toList();
            boolean team = q.teamScope() && q.people().stream().anyMatch(com.shifa.oms.chatbot.domain.PersonRef::teamLead);
            who = "by " + sentenceList(names) + (team ? (names.size() == 1 ? "'s team" : " (with their teams)") : "");
        }
        String where = "";
        if (includeChannel) {
            where = switch (q.channel()) {
                case ALL -> "";
                case PORTAL -> "from the portal";
                case SHOPIFY -> "from Shopify";
                case STORE -> "from in-shop (POS) sales";
            };
        }
        return spaced(who, where);
    }

    /** Joins the non-blank parts with single spaces. */
    static String spaced(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    /** The wording the Orders page shows for a status (the stored name stays technical). */
    static String statusLabel(OrderStatus status) {
        if (status == null) {
            return "Unknown";
        }
        return switch (status) {
            case PENDING_ADMIN_APPROVAL -> "Pending";
            case APPROVED -> "Confirmed";
            case LABEL_GENERATED -> "Tracking ID Assigned";
            case PACKED -> "Awaiting Handover";
            case HANDED_TO_DELIVERY -> "Handed to Delivery";
            case COURIER_ASSIGNED -> "Ready For Pickup";
            case DISPATCHED, IN_TRANSIT -> "In Transit";
            case OUT_FOR_DELIVERY -> "Out For Delivery";
            case DELIVERED -> "Delivered";
            case COD_COLLECTED -> "Collected on Delivery";
            case CLOSED -> "Closed";
            case CUSTOMER_REJECTED, RTO -> "Returned";
            case DELIVERY_FAILED -> "Delivery Failed";
            case REDISPATCH -> "Lost";
            case REJECTED -> "Rejected";
            case PAYMENT_REJECTED -> "Payment Rejected";
            case CANCELLED -> "Cancelled";
        };
    }

    /** The label of a lifecycle stage, as on the Orders page tabs. */
    static String stageLabel(OrderStatusGroup group) {
        if (group == null) {
            return "";
        }
        return switch (group) {
            case PENDING_APPROVAL -> "Pending Approval";
            case PROCESSING -> "Processing";
            case SHIPPED -> "Shipped";
            case DELIVERED -> "Delivered";
            case FAILED_RETURNED -> "Failed / Returned";
            case CANCELLED -> "Cancelled";
            case REJECTED -> "Rejected";
        };
    }

    static String paymentLabel(PaymentStatus status) {
        if (status == null) {
            return "Unknown";
        }
        return switch (status) {
            case COD -> "Pay on Delivery";
            case PARTIALLY_PAID -> "Partly paid upfront";
            case FULLY_PAID -> "Fully paid";
        };
    }

    static String verificationLabel(PaymentVerificationStatus status) {
        if (status == null) {
            return "Not applicable";
        }
        return switch (status) {
            case PENDING -> "Awaiting verification";
            case VERIFIED -> "Verified";
            case REJECTED -> "Rejected by verifier";
        };
    }

    static String dateTime(LocalDateTime t) {
        return t == null ? "unknown time" : DATE_TIME.format(t);
    }

    /** "+12.5%" / "-8%" / "n/a" when there is no earlier figure to compare with. */
    static String percentChange(BigDecimal now, BigDecimal before) {
        if (before == null || before.signum() == 0) {
            return "n/a";
        }
        BigDecimal pct = now.subtract(before).multiply(BigDecimal.valueOf(100))
                .divide(before, 1, RoundingMode.HALF_UP);
        String text = pct.stripTrailingZeros().toPlainString();
        return (pct.signum() > 0 ? "+" : "") + text + "%";
    }

    private static String group(String digits) {
        int n = digits.length();
        if (n <= 3) {
            return digits;
        }
        String lastThree = digits.substring(n - 3);
        String rest = digits.substring(0, n - 3);
        StringBuilder sb = new StringBuilder();
        int lead = rest.length() % 2;
        if (lead > 0) {
            sb.append(rest, 0, lead);
        }
        for (int i = lead; i < rest.length(); i += 2) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(rest, i, i + 2);
        }
        return sb + "," + lastThree;
    }
}
