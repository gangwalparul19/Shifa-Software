package com.shifa.oms.chatbot.domain;

import com.shifa.oms.chatbot.domain.ParsedQuestion.Breakdown;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Kind;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Metric;
import com.shifa.oms.chatbot.domain.ParsedQuestion.Topic;
import com.shifa.oms.dashboard.DashboardChannel;
import com.shifa.oms.order.OrderStatusGroup;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a free-text business question into a {@link ParsedQuestion} with plain
 * rules: no AI model and no network call. It reads a date phrase, channel words,
 * a ranking request, then matches staff and product names (tolerating typos)
 * against the live catalogue.
 *
 * <p>The parser never guesses silently. A name that follows "by / for / of" but
 * matches nothing is reported in {@link ParsedQuestion#unresolved()} so the
 * caller can refuse instead of answering for the whole company, and a name that
 * fits several people is reported in {@link ParsedQuestion#ambiguousPeople()}.
 *
 * <p>Pure and deterministic ("today" and the catalogue are passed in).
 */
public final class QuestionParser {

    private static final Pattern SALES_PEOPLE = Pattern.compile(
            "\\bsales?\\s*(?:persons?|people|men|man|reps?|executives?|agents?|team|staff|employees?)\\b");

    private static final String PERSON_DIM =
            "(?:salespersons?|salesmen|executives?|agents?|employees?|staff|sellers?|reps?|members?|persons?)";
    private static final String PRODUCT_DIM = "(?:products?|items?|medicines?|skus?)";
    private static final String RANKER = "(?:each|every|per|all|top|best|highest|lowest|worst|bottom|leading|most|least)";
    private static final String NUMBER_WORD = "one|two|three|four|five|six|seven|eight|nine|ten";

    private static final List<Pattern> PRODUCT_BREAKDOWN = List.of(
            Pattern.compile("\\bby\\s+(?:each\\s+|every\\s+|the\\s+|all\\s+)?" + PRODUCT_DIM + "\\b"),
            Pattern.compile("\\b" + RANKER + "\\s+(?:\\w+\\s+){0,2}?" + PRODUCT_DIM + "\\b"),
            Pattern.compile("\\b" + PRODUCT_DIM + "\\s+wise\\b|\\bwise\\s+" + PRODUCT_DIM + "\\b"),
            Pattern.compile("\\b(?:best|top)\\s*sell(?:ing|ers?)\\b|\\bmost\\s+(?:sold|selling)\\b"
                    + "|\\b(?:slow|fast)\\s*movers?\\b"),
            Pattern.compile("\\b(?:which|what)\\s+(?:\\w+\\s+)?" + PRODUCT_DIM + "\\b"));

    private static final List<Pattern> PERSON_BREAKDOWN = List.of(
            Pattern.compile("\\bby\\s+(?:each\\s+|every\\s+|the\\s+|all\\s+)?" + PERSON_DIM + "\\b"),
            Pattern.compile("\\b" + RANKER + "\\s+(?:\\w+\\s+){0,2}?" + PERSON_DIM + "\\b"),
            Pattern.compile("\\b" + PERSON_DIM + "\\s+wise\\b|\\bwise\\s+" + PERSON_DIM + "\\b"),
            Pattern.compile("\\bwho\\s+(?:\\w+\\s+){0,2}?(?:sold|sells?|selling|made|placed|punched|booked|got|"
                    + "generated|brought|leads?|tops?|performed|performing|ordered)\\b"),
            Pattern.compile("\\bleaderboard\\b|\\bleague\\s+table\\b|\\branking\\b"));

    private static final List<Pattern> CHANNEL_BREAKDOWN = List.of(
            Pattern.compile("\\bby\\s+(?:each\\s+|every\\s+|the\\s+)?(?:channels?|sources?)\\b"),
            Pattern.compile("\\b(?:channel|source)\\s*wise\\b|\\bwise\\s+(?:channel|source)\\b"),
            Pattern.compile("\\b(?:each|every|per)\\s+(?:channel|source)\\b"),
            Pattern.compile("\\bcompare\\s+(?:the\\s+)?(?:channels?|sources?)\\b"
                    + "|\\bchannels?\\s+(?:split|breakdown|comparison)\\b"));

    /** "bank1" / "qr2" written without a space. */
    private static final Pattern ACCOUNT_NUMBER_GLUE = Pattern.compile(
            "\\b(bank|banks|qr|upi|account|acc|scanner|wallet)(\\d{1,2})\\b");

    private static final Pattern MENTION_PORTAL = Pattern.compile("\\bportal\\b");
    private static final Pattern MENTION_SHOPIFY = Pattern.compile("\\bshopify\\b");
    private static final Pattern MENTION_STORE = Pattern.compile(
            "\\b(?:pos|walk\\s?in|counter|in\\s?shop|in\\s?store|offline|retail)\\b");
    private static final Pattern MENTION_ALL = Pattern.compile(
            "\\ball\\s+(?:the\\s+)?(?:sources?|channels?)\\b|\\bany\\s+source\\b|\\bcombined\\b");

    private static final Pattern TOP_N = Pattern.compile(
            "\\b(?:top|bottom|lowest|worst|best|highest)\\s+(\\d{1,2}|" + NUMBER_WORD + ")\\b"
                    + "|\\b(\\d{1,2}|" + NUMBER_WORD + ")\\s+(?:top|best|highest|lowest|worst|bottom)\\b");
    private static final Pattern SUPERLATIVE = Pattern.compile(
            "\\b(?:top|best|highest|most|leading|lowest|least|worst|bottom)\\b");
    private static final Pattern ASCENDING = Pattern.compile(
            "\\b(?:lowest|least|worst|bottom|fewest|slowest|slow)\\b");

    private static final Pattern HELP_PHRASE = Pattern.compile(
            "\\bwhat\\s+can\\s+you\\b|\\bhow\\s+(?:do|can|to)\\s+(?:i|we|you)\\b|\\bwhat\\s+do\\s+you\\b|\\bexamples?\\b");

    private static final Set<String> PERSON_DIM_WORDS = Set.of(
            "salesperson", "salespersons", "salesman", "salesmen", "executive", "executives", "agent", "agents",
            "employee", "employees", "staff", "seller", "sellers", "rep", "reps", "member", "members", "person",
            "persons");
    private static final Set<String> PRODUCT_DIM_WORDS = Set.of(
            "product", "products", "item", "items", "medicine", "medicines", "sku", "skus");
    private static final Set<String> DIM_LEADERS = Set.of("by", "the", "each", "every", "all", "a", "an");

    /** Parses {@code raw} against today's date and the live staff / product catalogue. */
    public ParsedQuestion parse(String raw, LocalDate today, List<PersonRef> people, List<ProductRef> catalogue) {
        return parse(raw, today, people, catalogue, List.of());
    }

    /**
     * Parses {@code raw} against today's date and the live staff, product and bank /
     * QR account catalogue.
     */
    public ParsedQuestion parse(String raw, LocalDate today, List<PersonRef> people, List<ProductRef> catalogue,
                                List<AccountRef> accountCatalogue) {
        String lower = PeriodParser.fixTypos(TextUtil.prepare(raw)).replaceAll("\\ba\\s*/\\s*c\\b", "account");

        // An order number is lifted out first so none of its digits can be read as a date or an amount.
        TopicReader.OrderRefs orderRefs = TopicReader.extractOrderCode(lower);
        lower = orderRefs.text();

        // Every period named, in the order the user wrote them ("today, yesterday, this week").
        List<Period> found = new ArrayList<>();
        String periodText = lower;
        for (int i = 0; i < 6; i++) {
            PeriodParser.Extraction e = PeriodParser.extract(periodText, today, " pxmark" + i + "x ");
            if (e.period() == null) {
                break;
            }
            found.add(e.period());
            periodText = e.remaining();
        }
        List<Period> periods = inWrittenOrder(found, periodText);
        periodText = periodText.replaceAll("pxmark\\d+x", " ");
        boolean periodDefaulted = periods.isEmpty();
        Period period = periodDefaulted ? Period.allTime() : periods.get(0);
        List<Period> extraPeriods = periodDefaulted ? List.of() : List.copyOf(periods.subList(1, periods.size()));

        String flat = SALES_PEOPLE.matcher(TextUtil.flatten(periodText)).replaceAll("salesperson");
        flat = ACCOUNT_NUMBER_GLUE.matcher(flat).replaceAll("$1 $2");
        List<String> tokens = TextUtil.tokens(flat);
        boolean[] used = new boolean[tokens.size()];

        // A specific order or customer: answered as a lookup, not as a business total.
        boolean lookupContext = tokens.stream().anyMatch(TopicReader.LOOKUP_CONTEXT::contains);
        // Next to "AWB" / "tracking" a long number is a courier tracking number, not a mobile.
        boolean trackingWords = tokens.stream().anyMatch(TopicReader.TRACKING_WORDS::contains);
        String mobile = orderRefs.orderCode() == null && !trackingWords ? TopicReader.findMobile(tokens, used) : null;
        String reference = orderRefs.orderCode() == null && mobile == null
                ? TopicReader.findReference(tokens, used, lookupContext) : null;
        if (orderRefs.orderCode() != null || reference != null) {
            return lookup(Topic.ORDER_LOOKUP, period, periodDefaulted, orderRefs.orderCode(), reference, null);
        }
        if (mobile != null) {
            return lookup(Topic.CUSTOMER_LOOKUP, period, periodDefaulted, null, null, mobile);
        }

        List<DashboardChannel> mentioned = channelMentions(flat);
        List<DashboardChannel> compareChannels = List.of();
        Breakdown breakdown;
        if (mentioned.size() >= 2) {
            breakdown = Breakdown.CHANNEL;
            compareChannels = mentioned;
        } else if (anyMatch(PRODUCT_BREAKDOWN, flat)) {
            breakdown = Breakdown.PRODUCT;
        } else if (anyMatch(PERSON_BREAKDOWN, flat)) {
            breakdown = Breakdown.PERSON;
        } else if (anyMatch(CHANNEL_BREAKDOWN, flat)) {
            breakdown = Breakdown.CHANNEL;
        } else {
            breakdown = Breakdown.NONE;
        }
        DashboardChannel channel = mentioned.size() == 1 ? mentioned.get(0) : DashboardChannel.ALL;

        TopicReader.AccountResult accountResult = TopicReader.matchAccounts(tokens, used, accountCatalogue);
        PersonResult personResult = matchPeople(tokens, used, people);
        Set<Integer> personTokens = indexesUsed(used, Collections.emptySet());
        List<ProductRef> products = matchProducts(tokens, used, catalogue);
        Set<Integer> productTokens = indexesUsed(used, personTokens);

        // "sales by salesperson Sameer Khan" names ONE person to filter on; it is not a ranking.
        if (breakdown == Breakdown.PERSON && followsDimension(tokens, personTokens, PERSON_DIM_WORDS)) {
            breakdown = Breakdown.NONE;
        }
        if (breakdown == Breakdown.PRODUCT && followsDimension(tokens, productTokens, PRODUCT_DIM_WORDS)) {
            breakdown = Breakdown.NONE;
        }

        // What the question is about: sales (default), money received per bank / QR account, cash on
        // delivery, the courier backlog, or "what needs attention".
        Set<Topic> topics = TopicReader.readTopics(flat, tokens, accountResult.mentioned(), periodDefaulted);
        boolean salesTopic = topics.contains(Topic.SALES);
        OrderStatusGroup stage = salesTopic ? TopicReader.stage(flat) : null;
        if (breakdown == Breakdown.NONE && stage == null && salesTopic && TopicReader.asksForStatusOverview(flat)) {
            breakdown = Breakdown.STATUS;
        }
        if (!salesTopic) {
            breakdown = Breakdown.NONE; // money / attention questions have no ranking
        }
        boolean average = TopicReader.asksForAverage(flat);

        boolean ordersWord = false;
        boolean salesWord = false;
        boolean unitsWord = false;
        boolean anyMetricWord = false;
        for (String t : tokens) {
            if (Vocabulary.METRIC.contains(t)) {
                anyMetricWord = true;
            }
            switch (t) {
                case "order", "orders" -> ordersWord = true;
                case "sale", "sales", "revenue", "amount", "worth", "turnover", "business", "income", "value" ->
                        salesWord = true;
                case "qty", "quantity", "quantities", "unit", "units", "piece", "pieces", "pcs", "pc", "bottle",
                     "bottles", "packs" -> unitsWord = true;
                default -> {
                }
            }
        }
        int named = (ordersWord ? 1 : 0) + (salesWord ? 1 : 0) + (unitsWord ? 1 : 0);
        Metric metric;
        if (named == 1) {
            metric = ordersWord ? Metric.ORDERS : salesWord ? Metric.SALES : Metric.UNITS;
        } else if (named == 0 && !products.isEmpty()) {
            metric = Metric.UNITS;
        } else {
            metric = Metric.SUMMARY;
        }

        boolean teamWord = tokens.contains("team") || tokens.contains("teams");
        boolean teamScope = teamWord && personResult.resolved().stream().anyMatch(PersonRef::teamLead);

        boolean unitsNeedProduct = metric == Metric.UNITS && products.isEmpty() && breakdown != Breakdown.PRODUCT;
        List<String> unresolved = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        collectLeftovers(tokens, used, unitsNeedProduct, unresolved, ignored);

        boolean moneyOrAttention = !(topics.size() == 1 && salesTopic);
        boolean accountEvidence = !accountResult.resolved().isEmpty() || !accountResult.ambiguous().isEmpty()
                || !accountResult.unresolved().isEmpty();
        boolean recognised = anyMetricWord || breakdown != Breakdown.NONE || !mentioned.isEmpty()
                || !personResult.resolved().isEmpty() || !personResult.ambiguous().isEmpty()
                || !products.isEmpty() || !periodDefaulted || !unresolved.isEmpty()
                || moneyOrAttention || stage != null || accountEvidence || average;
        if (!recognised) {
            boolean asksForHelp = tokens.stream().anyMatch(Vocabulary.GREETING::contains)
                    || HELP_PHRASE.matcher(flat).find();
            return ParsedQuestion.of(asksForHelp ? Kind.HELP : Kind.UNKNOWN, period);
        }

        int topN = parseTopN(flat);
        boolean ascending = ASCENDING.matcher(flat).find();
        return new ParsedQuestion(Kind.QUERY, metric, period, periodDefaulted, channel, compareChannels,
                personResult.resolved(), teamScope, personResult.ambiguous(), products, breakdown, topN, ascending,
                unresolved, ignored, topics, extraPeriods, accountResult.resolved(), accountResult.ambiguous(),
                accountResult.unresolved(), stage, null, null, null, average);
    }

    /** A question about one specific order (by order number or tracking number) or one customer (by mobile). */
    private static ParsedQuestion lookup(Topic topic, Period period, boolean periodDefaulted, String orderCode,
                                         String reference, String mobile) {
        return new ParsedQuestion(Kind.QUERY, Metric.SUMMARY, period, periodDefaulted, DashboardChannel.ALL,
                List.of(), List.of(), false, List.of(), List.of(), Breakdown.NONE, 10, false, List.of(), List.of(),
                EnumSet.of(topic), List.of(), List.of(), List.of(), List.of(), null, orderCode, reference, mobile,
                false);
    }

    /** The periods in the order they were written, without repeats. */
    private static List<Period> inWrittenOrder(List<Period> found, String textWithMarkers) {
        record Placed(Period period, int position) {
        }
        List<Placed> placed = new ArrayList<>();
        for (int i = 0; i < found.size(); i++) {
            placed.add(new Placed(found.get(i), textWithMarkers.indexOf("pxmark" + i + "x")));
        }
        placed.sort(Comparator.comparingInt(Placed::position));
        List<Period> ordered = new ArrayList<>();
        for (Placed p : placed) {
            if (!ordered.contains(p.period())) {
                ordered.add(p.period());
            }
        }
        return ordered;
    }

    // ------------------------------------------------------------------ channels

    private static List<DashboardChannel> channelMentions(String flat) {
        TreeMap<Integer, DashboardChannel> byPosition = new TreeMap<>();
        record Mention(Pattern pattern, DashboardChannel channel) {
        }
        for (Mention mention : List.of(
                new Mention(MENTION_PORTAL, DashboardChannel.PORTAL),
                new Mention(MENTION_SHOPIFY, DashboardChannel.SHOPIFY),
                new Mention(MENTION_STORE, DashboardChannel.STORE),
                new Mention(MENTION_ALL, DashboardChannel.ALL))) {
            Matcher m = mention.pattern().matcher(flat);
            while (m.find()) {
                byPosition.putIfAbsent(m.start(), mention.channel());
            }
        }
        // Distinct channels in the order the question mentioned them.
        return new ArrayList<>(new LinkedHashSet<>(byPosition.values()));
    }

    private static boolean anyMatch(List<Pattern> patterns, String text) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static int parseTopN(String flat) {
        Matcher m = TOP_N.matcher(flat);
        if (m.find()) {
            String value = m.group(1) != null ? m.group(1) : m.group(2);
            return Math.max(1, Math.min(50, numberOf(value)));
        }
        return SUPERLATIVE.matcher(flat).find() ? 5 : 10;
    }

    private static int numberOf(String value) {
        if (Character.isDigit(value.charAt(0))) {
            return Integer.parseInt(value);
        }
        return switch (value) {
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            default -> 10;
        };
    }

    // ------------------------------------------------------------------ people

    private record PersonResult(List<PersonRef> resolved, List<PersonRef> ambiguous) {
    }

    /** A staff member whose name words were found in the question. */
    private static final class Candidate {
        final PersonRef person;
        final Set<Integer> indexes;
        final boolean full;
        final int score;

        Candidate(PersonRef person, Set<Integer> indexes, boolean full, int score) {
            this.person = person;
            this.indexes = indexes;
            this.full = full;
            this.score = score;
        }
    }

    private static PersonResult matchPeople(List<String> tokens, boolean[] used, List<PersonRef> people) {
        List<Candidate> candidates = new ArrayList<>();
        for (PersonRef person : people) {
            List<String> nameWords = TextUtil.nameTokens(person.fullName()).stream()
                    .filter(w -> w.length() >= 2).toList();
            if (nameWords.isEmpty()) {
                continue;
            }
            Set<Integer> indexes = new LinkedHashSet<>();
            int score = 0;
            boolean solid = false;
            for (String word : nameWords) {
                int at = findWord(tokens, used, indexes, word, false);
                int points = 2;
                if (at < 0) {
                    at = findWord(tokens, used, indexes, word, true);
                    points = 1;
                }
                if (at >= 0) {
                    indexes.add(at);
                    score += points;
                    solid |= word.length() >= 3;
                }
            }
            if (!indexes.isEmpty() && solid) {
                candidates.add(new Candidate(person, indexes, indexes.size() == nameWords.size(), score));
            }
        }
        candidates.sort(Comparator.<Candidate, Boolean>comparing(c -> c.full).reversed()
                .thenComparing(c -> c.score, Comparator.reverseOrder())
                .thenComparing(c -> c.person.fullName()));

        List<Candidate> claimed = new ArrayList<>();
        Map<Candidate, List<Candidate>> ties = new HashMap<>();
        Set<Integer> claimedIndexes = new HashSet<>();
        for (Candidate c : candidates) {
            if (Collections.disjoint(c.indexes, claimedIndexes)) {
                claimed.add(c);
                claimedIndexes.addAll(c.indexes);
                continue;
            }
            for (Candidate winner : claimed) {
                if (winner.indexes.equals(c.indexes) && winner.full == c.full && winner.score == c.score) {
                    ties.computeIfAbsent(winner, k -> new ArrayList<>()).add(c);
                    break;
                }
            }
        }

        List<PersonRef> resolved = new ArrayList<>();
        List<PersonRef> ambiguous = new ArrayList<>();
        for (Candidate winner : claimed) {
            for (int i : winner.indexes) {
                used[i] = true;
            }
            List<Candidate> tied = ties.get(winner);
            if (tied == null) {
                resolved.add(winner.person);
            } else {
                ambiguous.add(winner.person);
                tied.forEach(t -> ambiguous.add(t.person));
            }
        }
        return new PersonResult(resolved, ambiguous);
    }

    private static int findWord(List<String> tokens, boolean[] used, Set<Integer> taken, String word, boolean fuzzy) {
        for (int i = 0; i < tokens.size(); i++) {
            if (used[i] || taken.contains(i)) {
                continue;
            }
            String token = tokens.get(i);
            if (!fuzzy ? token.equals(word) : !Vocabulary.isKnown(token) && TextUtil.sameWord(token, word)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether any of {@code matched} sits straight after a "by / the / each ... {dimension word}". */
    private static boolean followsDimension(List<String> tokens, Set<Integer> matched, Set<String> dimensionWords) {
        for (int i = 0; i + 1 < tokens.size(); i++) {
            if (dimensionWords.contains(tokens.get(i)) && matched.contains(i + 1)) {
                int before = i - 1;
                while (before >= 0 && DIM_LEADERS.contains(tokens.get(before)) && !tokens.get(before).equals("by")) {
                    before--;
                }
                if (before >= 0 && tokens.get(before).equals("by")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Set<Integer> indexesUsed(boolean[] used, Set<Integer> exclude) {
        Set<Integer> out = new HashSet<>();
        for (int i = 0; i < used.length; i++) {
            if (used[i] && !exclude.contains(i)) {
                out.add(i);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ products

    private record NamedProduct(ProductRef ref, List<String> words, String joined) {
    }

    private record Span(ProductRef product, int start, int end) {
    }

    private static List<ProductRef> matchProducts(List<String> tokens, boolean[] used, List<ProductRef> catalogue) {
        if (catalogue.isEmpty() || tokens.isEmpty()) {
            return List.of();
        }
        List<NamedProduct> named = new ArrayList<>();
        for (ProductRef ref : catalogue) {
            List<String> words = TextUtil.nameTokens(ref.name());
            String joined = String.join("", words);
            if (joined.length() >= 3) {
                named.add(new NamedProduct(ref, words, joined));
            }
        }

        // 1. The product's whole name appears (spacing ignored: "face wash" = "Facewash").
        List<Span> exact = new ArrayList<>();
        for (NamedProduct np : named) {
            for (int start = 0; start < tokens.size(); start++) {
                for (int len = 1; len <= 6 && start + len <= tokens.size(); len++) {
                    if (used[start + len - 1]) {
                        break;
                    }
                    if (TextUtil.join(tokens, start, start + len).equals(np.joined())) {
                        exact.add(new Span(np.ref(), start, start + len));
                    }
                }
            }
        }
        if (!exact.isEmpty()) {
            List<Span> kept = new ArrayList<>();
            for (Span s : exact) {
                boolean insideLonger = exact.stream().anyMatch(o -> o != s
                        && o.start() <= s.start() && o.end() >= s.end()
                        && (o.end() - o.start()) > (s.end() - s.start()));
                if (!insideLonger) {
                    kept.add(s);
                }
            }
            return claimExtended(kept, tokens, used, named);
        }

        // 2. Close spelling of one product name ("Gut Cleaner" for "Gut Cleanser").
        Map<Long, Double> bestRatio = new LinkedHashMap<>();
        Map<Long, ProductRef> refs = new HashMap<>();
        double overall = Double.MAX_VALUE;
        int bestStart = -1;
        int bestEnd = -1;
        for (int start = 0; start < tokens.size(); start++) {
            for (int len = 1; len <= 4 && start + len <= tokens.size(); len++) {
                if (used[start + len - 1]) {
                    break;
                }
                boolean hasUnknown = false;
                for (int i = start; i < start + len; i++) {
                    hasUnknown |= !Vocabulary.isKnown(tokens.get(i));
                }
                String gram = TextUtil.join(tokens, start, start + len);
                if (!hasUnknown || gram.length() < 5) {
                    continue;
                }
                for (NamedProduct np : named) {
                    int longest = Math.max(gram.length(), np.joined().length());
                    int distance = TextUtil.distance(gram, np.joined());
                    if (distance * 5 > longest) {
                        continue;
                    }
                    double ratio = (double) distance / longest;
                    bestRatio.merge(np.ref().id(), ratio, Double::min);
                    refs.put(np.ref().id(), np.ref());
                    if (ratio < overall) {
                        overall = ratio;
                        bestStart = start;
                        bestEnd = start + len;
                    }
                }
            }
        }
        if (!bestRatio.isEmpty()) {
            for (int i = bestStart; i < bestEnd; i++) {
                used[i] = true;
            }
            List<ProductRef> out = new ArrayList<>();
            for (Map.Entry<Long, Double> e : bestRatio.entrySet()) {
                if (e.getValue() <= overall + 0.03) {
                    out.add(refs.get(e.getKey()));
                }
            }
            return out;
        }

        // 3. Part of a name ("honey", "power gold"): every product containing ALL the typed words.
        List<ProductRef> partial = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            if (used[i] || Vocabulary.isKnown(tokens.get(i)) || tokens.get(i).length() < 3) {
                i++;
                continue;
            }
            int end = i;
            while (end < tokens.size() && !used[end] && !Vocabulary.isKnown(tokens.get(end))
                    && tokens.get(end).length() >= 3) {
                end++;
            }
            List<String> run = tokens.subList(i, end);
            List<ProductRef> hits = new ArrayList<>();
            for (NamedProduct np : named) {
                if (containsAll(np.words(), run)) {
                    hits.add(np.ref());
                }
            }
            if (!hits.isEmpty()) {
                partial.addAll(hits);
                for (int k = i; k < end; k++) {
                    used[k] = true;
                }
            }
            i = end;
        }
        return partial.stream().distinct().toList();
    }

    private static boolean containsAll(List<String> productWords, List<String> typed) {
        for (String word : typed) {
            boolean found = false;
            for (String pw : productWords) {
                if (TextUtil.sameWord(word, pw)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * Claims the matched products. When unknown words sit right next to a matched
     * name ("joint heal <capsule>"), they may be the rest of a longer product name,
     * so a product that contains every typed word replaces the shorter match.
     * Otherwise the extra words stay unclaimed and are reported, never skipped.
     */
    private static List<ProductRef> claimExtended(List<Span> spans, List<String> tokens, boolean[] used,
                                                  List<NamedProduct> named) {
        Map<Long, ProductRef> distinct = new LinkedHashMap<>();
        for (Span s : spans) {
            int left = s.start();
            while (left > 0 && !used[left - 1] && isFreeWord(tokens.get(left - 1))) {
                left--;
            }
            int right = s.end();
            while (right < tokens.size() && !used[right] && isFreeWord(tokens.get(right))) {
                right++;
            }
            List<ProductRef> chosen = List.of(s.product());
            int from = s.start();
            int to = s.end();
            if (left < s.start() || right > s.end()) {
                List<String> typed = tokens.subList(left, right);
                List<ProductRef> longer = new ArrayList<>();
                for (NamedProduct np : named) {
                    if (containsAll(np.words(), typed)) {
                        longer.add(np.ref());
                    }
                }
                if (!longer.isEmpty()) {
                    chosen = longer;
                    from = left;
                    to = right;
                }
            }
            for (ProductRef p : chosen) {
                distinct.putIfAbsent(p.id(), p);
            }
            for (int i = from; i < to; i++) {
                used[i] = true;
            }
        }
        return new ArrayList<>(distinct.values());
    }

    private static boolean isFreeWord(String token) {
        return !Vocabulary.isKnown(token) && token.length() >= 3;
    }

    // ------------------------------------------------------------------ leftovers

    /**
     * Sorts words nothing could explain. A run that follows "by / for / of ..."
     * (or a units question with no product) is a name the bot must not skip; any
     * other stray word is just reported as ignored.
     */
    private static void collectLeftovers(List<String> tokens, boolean[] used, boolean unitsNeedProduct,
                                         List<String> unresolved, List<String> ignored) {
        int i = 0;
        while (i < tokens.size()) {
            if (!isLeftover(tokens, used, i)) {
                i++;
                continue;
            }
            int end = i;
            while (end < tokens.size() && isLeftover(tokens, used, end)) {
                end++;
            }
            String phrase = String.join(" ", tokens.subList(i, end));
            int before = i - 1;
            while (before >= 0 && Vocabulary.NAME_FILLER.contains(tokens.get(before))) {
                before--;
            }
            boolean afterLeader = before >= 0 && (Vocabulary.PREPOSITIONS.contains(tokens.get(before))
                    || Vocabulary.ENTITY_LEADERS.contains(tokens.get(before)));
            // A stray word touching a matched name is most likely part of a name we failed to resolve.
            boolean touchesMatch = (i > 0 && used[i - 1]) || (end < tokens.size() && used[end]);
            if (afterLeader || touchesMatch || unitsNeedProduct) {
                unresolved.add(phrase);
            } else {
                ignored.add(phrase);
            }
            i = end;
        }
    }

    private static boolean isLeftover(List<String> tokens, boolean[] used, int i) {
        String t = tokens.get(i);
        return !used[i] && t.length() >= 3 && !Vocabulary.isKnown(t);
    }
}
