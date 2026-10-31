package com.shifa.oms.chatbot.domain;

import com.shifa.oms.chatbot.domain.ParsedQuestion.Topic;
import com.shifa.oms.order.OrderStatusGroup;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads what a question is about beyond plain sales: a specific order or
 * customer, bank / QR receipts, cash on delivery, "what needs attention" and
 * lifecycle stages. Pure text rules, shared by {@link QuestionParser}.
 */
final class TopicReader {

    private TopicReader() {
    }

    // ------------------------------------------------------------------ order numbers

    /** The order number found in a question (upper case) and the text with it removed. */
    record OrderRefs(String orderCode, String text) {
    }

    private static final Pattern ORDER_CODE_COMPACT = Pattern.compile(
            "\\bshr[-_ ]?(\\d{8})[-_ ]?([a-z0-9]{4})\\b");
    private static final Pattern ORDER_CODE_LEGACY = Pattern.compile(
            "\\bshr-([a-z0-9]+(?:-[a-z0-9]+)*)\\b");

    /** Words that make a bare id-like word ("wjs7") a lookup rather than noise. */
    static final Set<String> LOOKUP_CONTEXT = Set.of(
            "order", "orders", "status", "track", "tracking", "awb", "waybill", "consignment", "shipment", "parcel",
            "where", "details", "detail", "update", "number", "no", "id", "check", "find");

    /** Words that make a 10-digit number a courier tracking number rather than a mobile. */
    static final Set<String> TRACKING_WORDS = Set.of(
            "awb", "tracking", "track", "waybill", "consignment", "shipment", "parcel", "courier");

    static OrderRefs extractOrderCode(String lower) {
        Matcher m = ORDER_CODE_COMPACT.matcher(lower);
        if (m.find()) {
            return new OrderRefs("SHR-" + m.group(1) + "-" + m.group(2).toUpperCase(Locale.ROOT), cut(lower, m));
        }
        m = ORDER_CODE_LEGACY.matcher(lower);
        if (m.find()) {
            return new OrderRefs("SHR-" + m.group(1).toUpperCase(Locale.ROOT), cut(lower, m));
        }
        return new OrderRefs(null, lower);
    }

    private static String cut(String text, Matcher m) {
        return text.substring(0, m.start()) + " " + text.substring(m.end());
    }

    /** A 10-digit Indian mobile number among the tokens; marks it used. */
    static String findMobile(List<String> tokens, boolean[] used) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!used[i] && tokens.get(i).matches("[6-9]\\d{9}")) {
                used[i] = true;
                return tokens.get(i);
            }
        }
        return null;
    }

    /**
     * A tracking / courier / QuikShip number among the tokens: a number of five
     * or more digits, or (only when the question is clearly a lookup) a short
     * mixed letters-and-digits word such as the 4-character end of an order code.
     */
    static String findReference(List<String> tokens, boolean[] used, boolean lookupContext) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (used[i]) {
                continue;
            }
            if (t.matches("\\d{5,}")) {
                used[i] = true;
                return t;
            }
        }
        if (lookupContext) {
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                if (!used[i] && t.length() >= 4 && t.length() <= 12 && t.matches("[a-z0-9]+")
                        && t.matches(".*[a-z].*") && t.matches(".*\\d.*")) {
                    used[i] = true;
                    return t;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ bank / QR accounts

    /** Resolved accounts, plus those a typed reference could mean (or did not match at all). */
    record AccountResult(List<AccountRef> resolved, List<AccountRef> ambiguous, List<String> unresolved,
                         boolean mentioned) {
    }

    private static final Set<String> ACCOUNT_WORDS = Set.of(
            "bank", "banks", "qr", "qrs", "upi", "account", "accounts", "acc", "scanner", "scanners", "wallet");

    /** Words in an account's display name that do not tell accounts apart. */
    private static final Set<String> GENERIC_ACCOUNT_WORDS = Set.of(
            "bank", "banks", "qr", "qrs", "upi", "account", "accounts", "acc", "scanner", "scanners", "wallet",
            "shifa", "a", "c", "the", "and");

    static AccountResult matchAccounts(List<String> tokens, boolean[] used, List<AccountRef> accounts) {
        boolean mentioned = false;
        record Ref(String word, int number) {
        }
        List<Ref> refs = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (used[i] || !ACCOUNT_WORDS.contains(tokens.get(i))) {
                continue;
            }
            mentioned = true;
            boolean any = false;
            int j = i + 1;
            while (j < tokens.size()) {
                String t = tokens.get(j);
                if (!used[j] && t.matches("\\d{1,2}")) {
                    refs.add(new Ref(singular(tokens.get(i)), Integer.parseInt(t)));
                    used[j] = true;
                    any = true;
                    j++;
                } else if (any && (t.equals("and") || t.equals("or") || t.equals("plus") || t.equals("also"))
                        && j + 1 < tokens.size() && tokens.get(j + 1).matches("\\d{1,2}")) {
                    j++;
                } else {
                    break;
                }
            }
            if (any) {
                used[i] = true;
            }
        }

        Map<Long, AccountRef> resolved = new LinkedHashMap<>();
        List<AccountRef> ambiguous = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Set<Integer> refNumbers = new java.util.HashSet<>();
        for (Ref ref : refs) {
            refNumbers.add(ref.number());
            List<AccountRef> withNumber = new ArrayList<>();
            for (AccountRef a : accounts) {
                if (numbersIn(a.name()).contains(ref.number())) {
                    withNumber.add(a);
                }
            }
            List<AccountRef> pick = withNumber;
            if (pick.size() > 1) {
                List<AccountRef> narrowed = new ArrayList<>();
                for (AccountRef a : pick) {
                    if (TextUtil.nameTokens(a.name()).stream().map(TopicReader::singular).anyMatch(ref.word()::equals)) {
                        narrowed.add(a);
                    }
                }
                if (!narrowed.isEmpty()) {
                    pick = narrowed;
                }
            }
            if (pick.size() == 1) {
                resolved.putIfAbsent(pick.get(0).id(), pick.get(0));
            } else if (pick.size() > 1) {
                ambiguous.addAll(pick);
            } else {
                unresolved.add(ref.word() + " " + ref.number());
            }
        }

        // Match by a distinctive word of the name ("HDFC" finds "HDFC Current"). Ordinary words such as
        // "current" never count. When several accounts match, the ones matching the most words win.
        Map<AccountRef, List<Integer>> byName = new LinkedHashMap<>();
        int bestHits = 0;
        for (AccountRef a : accounts) {
            if (resolved.containsKey(a.id())) {
                continue;
            }
            List<String> nameWords = TextUtil.nameTokens(a.name());
            if (phraseUsed(tokens, used, String.join("", nameWords))) {
                resolved.put(a.id(), a);
                mentioned = true;
                continue;
            }
            List<String> distinct = nameWords.stream()
                    .filter(w -> !GENERIC_ACCOUNT_WORDS.contains(w) && !w.matches("\\d+") && !Vocabulary.isKnown(w))
                    .toList();
            if (distinct.isEmpty()) {
                continue;
            }
            Set<Integer> numbers = numbersIn(a.name());
            if (!refNumbers.isEmpty() && !numbers.isEmpty() && !refNumbers.containsAll(numbers)) {
                continue;
            }
            List<Integer> hits = new ArrayList<>();
            for (String word : distinct) {
                int at = indexOfWord(tokens, used, word);
                if (at >= 0 && !hits.contains(at)) {
                    hits.add(at);
                }
            }
            if (!hits.isEmpty()) {
                byName.put(a, hits);
                bestHits = Math.max(bestHits, hits.size());
            }
        }
        for (Map.Entry<AccountRef, List<Integer>> e : byName.entrySet()) {
            if (e.getValue().size() == bestHits) {
                resolved.put(e.getKey().id(), e.getKey());
                e.getValue().forEach(i -> used[i] = true);
                mentioned = true;
            }
        }
        return new AccountResult(new ArrayList<>(resolved.values()), ambiguous, unresolved, mentioned);
    }

    private static String singular(String word) {
        return word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
    }

    private static Set<Integer> numbersIn(String name) {
        Set<Integer> out = new java.util.HashSet<>();
        for (String t : TextUtil.nameTokens(name)) {
            if (t.matches("\\d{1,2}")) {
                out.add(Integer.parseInt(t));
            }
        }
        return out;
    }

    private static int indexOfWord(List<String> tokens, boolean[] used, String word) {
        for (int i = 0; i < tokens.size(); i++) {
            if (!used[i] && tokens.get(i).equals(word)) {
                return i;
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (!used[i] && !Vocabulary.isKnown(tokens.get(i)) && TextUtil.sameWord(tokens.get(i), word)) {
                return i;
            }
        }
        return -1;
    }

    /** Finds the joined phrase among consecutive unused tokens and marks them used. */
    private static boolean phraseUsed(List<String> tokens, boolean[] used, String joined) {
        if (joined.length() < 3) {
            return false;
        }
        for (int start = 0; start < tokens.size(); start++) {
            for (int len = 1; len <= 5 && start + len <= tokens.size(); len++) {
                if (used[start + len - 1]) {
                    break;
                }
                if (TextUtil.join(tokens, start, start + len).equals(joined)) {
                    for (int i = start; i < start + len; i++) {
                        used[i] = true;
                    }
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ subject of the question

    private static final Pattern BANK = Pattern.compile("\\b(?:banks?|qrs?|upi|scanners?)\\b");
    private static final Pattern RECEIPT = Pattern.compile(
            "\\b(?:received|receive|receipts?|credited|deposited|upfront|advance)\\b");
    private static final Pattern PAYMENT = Pattern.compile("\\bpayments?\\b");
    private static final Pattern COD = Pattern.compile(
            "\\bcod\\b|\\bcash\\s+on\\s+delivery\\b|\\bcollect(?:ed|ion)?\\s+(?:on|at|upon)\\s+delivery\\b"
                    + "|\\bto\\s+collect\\b|\\bpay(?:able|ment)?\\s+on\\s+delivery\\b|\\bon\\s+delivery\\b");
    private static final Pattern COURIER = Pattern.compile(
            "\\b(?:courier|partner|delhivery|quikship\\w*|remittance|remitted|settle(?:d|ment)?|unsettled|receivables?)\\b");
    private static final Pattern BACKLOG = Pattern.compile(
            "\\b(?:pending|outstanding|unsettled|owe[sd]?|due|balance|receivables?|remaining)\\b");
    private static final Pattern MONEY_STILL = Pattern.compile(
            "\\bstill\\s+(?:to\\s+)?collect\\b|\\bmoney\\s+(?:to\\s+collect|pending|outstanding)\\b|\\bcollectible\\b"
                    + "|\\btotal\\s+(?:to\\s+collect|outstanding|pending)\\b"
                    + "|\\bhow\\s+much\\s+(?:is\\s+|are\\s+)?(?:still\\s+)?(?:pending|outstanding|due)\\b");
    private static final Pattern ATTENTION = Pattern.compile(
            "\\b(?:needs?|needing|require[sd]?)\\s+(?:my\\s+)?attention\\b|\\battention\\b|\\burgent\\b|\\balerts?\\b"
                    + "|\\bexceptions?\\b|\\bbacklog\\b|\\bto\\s?do\\b|\\bpending\\s+work\\b|\\bwhat(?:s|\\s+is)\\s+pending\\b"
                    + "|\\bsnapshot\\b|\\boverview\\b|\\bhow\\s+is\\s+(?:the\\s+)?business\\b"
                    + "|\\bbusiness\\s+(?:summary|status|health)\\b|\\bpending\\s+payments?\\b"
                    + "|\\bpayments?\\s+(?:pending|awaiting|waiting)\\b|\\bpayment\\s+verification\\b|\\bunverified\\b"
                    + "|\\bto\\s+verify\\b|\\bawaiting\\s+verification\\b");
    private static final Pattern MONEY_WORD = Pattern.compile(
            "\\b(?:amount|money|payments?|rupees|rs|cash)\\b|\\bhow\\s+much\\b");
    private static final Pattern STATUS_WORD = Pattern.compile("\\bstatus(?:es)?\\b|\\bstages?\\b|\\bpipeline\\b");
    private static final Pattern AVERAGE = Pattern.compile(
            "\\baverage\\b|\\bavg\\b|\\baov\\b|\\bper\\s+order\\b|\\bticket\\s+size\\b");

    /** What the question is about. SALES is the default when nothing more specific is named. */
    static Set<Topic> readTopics(String flat, List<String> tokens, boolean accountsMentioned,
                                 boolean periodDefaulted) {
        boolean ordersWord = tokens.contains("order") || tokens.contains("orders");
        boolean salesWords = ordersWord || tokens.stream().anyMatch(t -> Set.of(
                "sale", "sales", "revenue", "unit", "units", "qty", "quantity", "quantities", "turnover", "business")
                .contains(t));
        boolean bank = BANK.matcher(flat).find();
        boolean receiptWord = RECEIPT.matcher(flat).find();
        boolean cod = COD.matcher(flat).find();
        boolean courier = COURIER.matcher(flat).find();
        boolean backlog = BACKLOG.matcher(flat).find();
        boolean moneyStill = MONEY_STILL.matcher(flat).find();
        boolean attention = ATTENTION.matcher(flat).find();

        Set<Topic> topics = EnumSet.noneOf(Topic.class);
        if (attention && !cod && !bank && !receiptWord && !accountsMentioned) {
            topics.add(Topic.ATTENTION);
            return topics;
        }
        boolean receipts = bank || accountsMentioned
                || (!attention && PAYMENT.matcher(flat).find())
                || (receiptWord && (!ordersWord || MONEY_WORD.matcher(flat).find()));
        if (receipts) {
            topics.add(Topic.RECEIPTS);
        }
        if (cod || moneyStill) {
            boolean courierBacklog = moneyStill || (courier && (cod || backlog)) || (cod && periodDefaulted);
            topics.add(courierBacklog ? Topic.COD_COURIER : Topic.COD);
        } else if (courier && backlog) {
            topics.add(Topic.COD_COURIER);
        }
        if (salesWords || topics.isEmpty()) {
            topics.add(Topic.SALES);
        }
        return topics;
    }

    static boolean asksForStatusOverview(String flat) {
        return STATUS_WORD.matcher(flat).find();
    }

    static boolean asksForAverage(String flat) {
        return AVERAGE.matcher(flat).find();
    }

    // ------------------------------------------------------------------ lifecycle stage

    private static final Map<OrderStatusGroup, Pattern> STAGE_WORDS = new LinkedHashMap<>();

    static {
        STAGE_WORDS.put(OrderStatusGroup.REJECTED, Pattern.compile("\\brejected\\b|\\brejections?\\b"));
        STAGE_WORDS.put(OrderStatusGroup.CANCELLED, Pattern.compile("\\bcancel(?:l)?ed\\b|\\bcancellations?\\b"));
        STAGE_WORDS.put(OrderStatusGroup.FAILED_RETURNED, Pattern.compile(
                "\\breturned\\b|\\brto\\b|\\bfailed\\b|\\bundelivered\\b|\\blost\\b|\\bredispatch\\b"
                        + "|\\bcustomer\\s+rejected\\b|\\bdelivery\\s+failed\\b"));
        STAGE_WORDS.put(OrderStatusGroup.DELIVERED, Pattern.compile("\\bdelivered\\b|\\bcompleted\\b"));
        STAGE_WORDS.put(OrderStatusGroup.SHIPPED, Pattern.compile(
                "\\bshipped\\b|\\bin\\s+transit\\b|\\bout\\s+for\\s+delivery\\b|\\bon\\s+the\\s+way\\b"
                        + "|\\bwith\\s+(?:the\\s+)?courier\\b|\\bdispatched\\b|\\bready\\s+for\\s+pickup\\b"
                        + "|\\bhanded\\s+to\\s+delivery\\b"));
        STAGE_WORDS.put(OrderStatusGroup.PROCESSING, Pattern.compile(
                "\\bprocessing\\b|\\bbeing\\s+prepared\\b|\\bawaiting\\s+(?:handover|packing|dispatch)\\b|\\bto\\s+pack\\b"
                        + "|\\bready\\s+to\\s+(?:ship|pack)\\b|\\blabel\\s+generated\\b|\\btracking\\s+id\\s+assigned\\b"
                        + "|\\bconfirmed\\s+orders?\\b|\\bpacked\\b"));
        STAGE_WORDS.put(OrderStatusGroup.PENDING_APPROVAL, Pattern.compile(
                "\\b(?:pending|awaiting|waiting)\\s+(?:for\\s+)?(?:admin\\s+)?approvals?\\b|\\bto\\s+be\\s+approved\\b"
                        + "|\\bunapproved\\b|\\bnot\\s+(?:yet\\s+)?approved\\b|\\bpending\\s+orders?\\b"
                        + "|\\borders?\\s+(?:are\\s+|is\\s+)?pending\\b"));
    }

    /** The lifecycle stage the question names ("pending approval", "delivered", "returned"), or null. */
    static OrderStatusGroup stage(String flat) {
        for (Map.Entry<OrderStatusGroup, Pattern> e : STAGE_WORDS.entrySet()) {
            if (e.getValue().matcher(flat).find()) {
                return e.getKey();
            }
        }
        return null;
    }
}
