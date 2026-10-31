package com.shifa.oms.chatbot.domain;

import java.util.Set;

/**
 * Words the parser understands as part of the question's own grammar (metrics,
 * channels, periods, filler). Anything else left over after the person and
 * product matchers have run is treated as a name the bot could not resolve.
 */
final class Vocabulary {

    private Vocabulary() {
    }

    static final Set<String> STOP = Set.of(
            "a", "an", "the", "of", "for", "from", "by", "in", "on", "at", "to", "and", "or", "is", "are", "was",
            "were", "be", "been", "am", "me", "my", "our", "we", "us", "you", "your", "yours", "please", "pls", "plz",
            "can", "could", "would", "should", "will", "shall", "tell", "show", "give", "get", "list", "what", "whats",
            "which", "who", "whom", "how", "many", "much", "did", "do", "does", "done", "has", "have", "had", "there",
            "this", "that", "these", "those", "it", "its", "with", "about", "as", "any", "some", "all", "every", "each",
            "per", "also", "only", "just", "kindly", "so", "if", "than", "then", "since", "till", "until", "up", "out",
            "over", "into", "vs", "versus", "compare", "comparison", "between", "total", "totals", "number", "count",
            "no", "nos", "need", "want", "know", "let", "see", "check", "find", "fetch", "display", "ok", "okay",
            "sir", "madam", "mam", "mrs", "shri", "bhai", "dear", "boss", "bro");

    static final Set<String> METRIC = Set.of(
            "order", "orders", "sale", "sales", "sold", "sell", "sells", "selling", "revenue", "amount", "worth",
            "turnover", "business", "income", "value", "qty", "quantity", "quantities", "unit", "units", "piece",
            "pieces", "pcs", "pc", "bottle", "bottles", "pack", "packs", "placed", "place", "places", "punched",
            "booked", "made", "generated", "received", "raised");

    static final Set<String> SORT = Set.of(
            "top", "best", "highest", "most", "maximum", "max", "leading", "bottom", "lowest", "least", "worst",
            "minimum", "min", "first", "rank", "ranking", "leaderboard", "wise", "fastest", "slowest", "slow", "fast",
            "fewest", "performing", "performer", "performers", "performance");

    static final Set<String> DIMENSION = Set.of(
            "salesperson", "salespersons", "salesman", "salesmen", "person", "persons", "people", "executive",
            "executives", "agent", "agents", "employee", "employees", "staff", "seller", "sellers", "rep", "reps",
            "member", "members", "team", "teams", "product", "products", "item", "items", "sku", "skus", "medicine",
            "medicines", "channel", "channels", "source", "sources", "breakdown", "split", "league", "table");

    static final Set<String> CHANNEL = Set.of(
            "portal", "shopify", "pos", "walkin", "walk", "counter", "inshop", "instore", "shop", "store", "offline",
            "retail", "combined", "everywhere", "online", "any");

    static final Set<String> PERIOD = Set.of(
            "today", "yesterday", "tonight", "week", "weeks", "month", "months", "year", "years", "day", "days",
            "daily", "weekly", "monthly", "yearly", "current", "last", "past", "previous", "latest", "recent", "now",
            "date", "time", "ytd", "mtd", "wtd", "fy", "financial", "fiscal", "quarter", "lifetime", "overall", "ever",
            "far", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug", "sep", "sept", "oct", "nov", "dec");

    static final Set<String> NUMBER_WORDS = Set.of(
            "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten");

    static final Set<String> GREETING = Set.of(
            "hi", "hello", "hey", "help", "namaste", "thanks", "thank", "shifa", "bot", "assistant");

    /** Words that introduce a filter value ("by Rahul", "for Gut Cleaner"). */
    static final Set<String> PREPOSITIONS = Set.of(
            "by", "for", "of", "from", "named", "called", "under", "with", "about");

    /** "how many Gut Cleanser ...": the word after these is a thing being counted. */
    static final Set<String> ENTITY_LEADERS = Set.of("many", "much");

    /** Words skipped when looking for the preposition before an unknown name. */
    static final Set<String> NAME_FILLER = Set.of(
            "the", "a", "an", "my", "our", "mr", "mrs", "ms", "shri", "sir", "madam", "salesperson", "executive",
            "agent", "product", "item", "person");

    /** Words that name a subject other than plain sales: bank accounts, COD, order status, lookups. */
    static final Set<String> TOPIC = wordsOf(
            "bank", "banks", "qr", "qrs", "upi", "account", "accounts", "acc", "scanner", "scanners", "wallet",
            "receipt", "receipts", "receive", "credited", "deposited", "upfront", "advance", "prepaid", "payment",
            "payments", "paid", "cod", "cash", "delivery", "collect", "collected", "collection", "courier", "partner",
            "quikship", "quikshipx", "delhivery", "remittance", "remitted", "settlement", "settled", "unsettled",
            "receivable", "receivables", "pending", "approval", "approvals", "approve", "approved", "unapproved",
            "awaiting", "waiting", "processing", "prepared", "preparing", "confirmed", "handed", "handover",
            "assigned", "ship", "shipped", "dispatched", "transit", "delivered", "completed", "returned", "rto",
            "redispatch", "failed", "cancelled", "canceled", "cancellations", "rejected", "rejections", "status",
            "stage", "stages", "pipeline", "attention", "exceptions", "exception", "alerts", "alert", "backlog",
            "urgent", "overview", "snapshot", "average", "avg", "aov", "ticket", "customer", "customers", "mobile",
            "phone", "contact", "cell", "whatsapp", "awb", "tracking", "track", "shipment", "parcel", "consignment",
            "waybill", "verified", "verification", "unverified", "verify", "owe", "owes", "owed", "due", "balance",
            "outstanding", "payable", "pickup", "ready", "label", "packing", "packed", "lost", "undelivered", "origin",
            "return", "rupees", "money", "rs", "todo", "work", "recent", "latest", "last", "id", "etc", "still",
            "plus", "details", "detail", "info", "information", "update", "where", "happened", "currently", "already",
            "yet", "claim", "claims", "separately", "together");

    private static Set<String> wordsOf(String... words) {
        return Set.copyOf(new java.util.HashSet<>(java.util.Arrays.asList(words)));
    }

    /** True for any word the question grammar already accounts for (or a bare number). */
    static boolean isKnown(String token) {
        return STOP.contains(token) || METRIC.contains(token) || SORT.contains(token)
                || DIMENSION.contains(token) || CHANNEL.contains(token) || PERIOD.contains(token)
                || NUMBER_WORDS.contains(token) || GREETING.contains(token) || TOPIC.contains(token)
                || !TextUtil.isAlphabetic(token);
    }
}
