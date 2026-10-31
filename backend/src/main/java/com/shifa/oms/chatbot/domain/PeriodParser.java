package com.shifa.oms.chatbot.domain;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the date phrase in a question ("today", "last month", "25 Oct",
 * "last 7 days", "this financial year" ...) and turns it into a {@link Period}.
 *
 * <p>Pure and deterministic: "today" is passed in, so the same question always
 * resolves the same way in tests. Weeks start on Monday and the financial year
 * runs 1 April to 31 March (Indian convention). Numeric dates are read
 * day-first ({@code 25/10/2026}).
 */
public final class PeriodParser {

    private PeriodParser() {
    }

    /** The resolved period (or {@code null} when none was found) and the text with that phrase removed. */
    public record Extraction(Period period, String remaining) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_NO_YEAR = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    static final String MONTH_NAMES = "january|february|march|april|may|june|july|august|september|october|november|december"
            + "|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec";

    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{1,2})-(\\d{1,2})\\b");
    private static final Pattern DMY = Pattern.compile("\\b(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{4}|\\d{2})\\b");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+(?:of\\s+)?(" + MONTH_NAMES + ")\\b(?:\\s*,?\\s*(\\d{4})\\b)?");
    private static final Pattern MONTH_DAY = Pattern.compile(
            "\\b(" + MONTH_NAMES + ")\\s+(\\d{1,2})(?!\\d)(?:st|nd|rd|th)?(?:\\s*,?\\s*(\\d{4})\\b)?");
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "(?:\\b(in|of|for|during|from|since)\\s+)?\\b(" + MONTH_NAMES + ")\\b(?:\\s+(\\d{4})\\b)?");

    private static final Pattern LAST_N_DAYS = Pattern.compile("\\b(?:last|past|previous)\\s+(\\d{1,3})\\s+days?\\b");
    private static final Pattern THIS_FY = Pattern.compile(
            "\\b(?:this|current)\\s+(?:financial\\s+year|fiscal\\s+year|fy|f\\s?y)\\b|\\bfy\\s+to\\s+date\\b");
    private static final Pattern LAST_FY = Pattern.compile(
            "\\b(?:last|previous|prior)\\s+(?:financial\\s+year|fiscal\\s+year|fy|f\\s?y)\\b");
    private static final Pattern THIS_WEEK = Pattern.compile("\\b(?:this|current)\\s+week\\b|\\bweek\\s+to\\s+date\\b|\\bwtd\\b");
    private static final Pattern LAST_WEEK = Pattern.compile("\\b(?:last|previous)\\s+week\\b");
    private static final Pattern THIS_MONTH = Pattern.compile("\\b(?:this|current)\\s+month\\b|\\bmonth\\s+to\\s+date\\b|\\bmtd\\b");
    private static final Pattern LAST_MONTH = Pattern.compile("\\b(?:last|previous)\\s+month\\b");
    private static final Pattern THIS_YEAR = Pattern.compile("\\b(?:this|current)\\s+year\\b|\\byear\\s+to\\s+date\\b|\\bytd\\b");
    private static final Pattern LAST_YEAR = Pattern.compile("\\b(?:last|previous)\\s+year\\b");
    private static final Pattern YESTERDAY = Pattern.compile("\\byesterday\\b");
    private static final Pattern TODAY = Pattern.compile("\\btoday\\b");
    private static final Pattern ALL_TIME = Pattern.compile(
            "\\ball\\s+time\\b|\\boverall\\b|\\blifetime\\b|\\btill\\s+date\\b|\\bto\\s+date\\b|\\bso\\s+far\\b"
                    + "|\\bsince\\s+(?:the\\s+)?(?:beginning|start|launch)\\b|\\bever\\b");

    private static final Pattern WORD = Pattern.compile("[a-z]+");

    /**
     * Repairs common typos of "yesterday" and "today" ("yerterday", "yestarday",
     * "todya") so they are read as the intended period. Only these two words are
     * touched, and only when the typed word is within two edits (one for "today").
     */
    public static String fixTypos(String lower) {
        Matcher m = WORD.matcher(lower);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            String word = m.group();
            String fixed = word;
            if (word.length() >= 7 && !word.equals("yesterday") && word.charAt(0) == 'y'
                    && TextUtil.distance(word, "yesterday") <= 2) {
                fixed = "yesterday";
            } else if (word.length() >= 4 && word.length() <= 6 && !word.equals("today") && word.charAt(0) == 't'
                    && TextUtil.distance(word, "today") == 1 && !word.equals("toady") && !word.equals("toad")) {
                fixed = "today";
            }
            out.append(lower, last, m.start()).append(fixed);
            last = m.end();
        }
        return out.append(lower.substring(last)).toString();
    }

    /**
     * Extracts the first date phrase found in {@code lower} (already lower-cased,
     * possessives stripped). Explicit dates win over relative phrases, which win
     * over a bare month name.
     */
    public static Extraction extract(String lower, LocalDate today) {
        return extract(lower, today, " ");
    }

    /**
     * As {@link #extract(String, LocalDate)}, but leaves {@code marker} where the
     * phrase was instead of a blank. Callers that pull out several periods use a
     * different marker per call so they can later tell in which order the user
     * wrote them.
     */
    public static Extraction extract(String lower, LocalDate today, String marker) {
        Matcher m = ISO.matcher(lower);
        while (m.find()) {
            LocalDate d = date(num(m, 1), num(m, 2), num(m, 3));
            if (d != null) {
                return done(single(d, today), lower, m, marker);
            }
        }

        m = DMY.matcher(lower);
        while (m.find()) {
            int year = num(m, 3);
            LocalDate d = date(year < 100 ? 2000 + year : year, num(m, 2), num(m, 1));
            if (d != null) {
                return done(single(d, today), lower, m, marker);
            }
        }

        m = DAY_MONTH.matcher(lower);
        while (m.find()) {
            LocalDate d = dayInMonth(num(m, 1), monthNumber(m.group(2)), m.group(3), today);
            if (d != null) {
                return done(single(d, today), lower, m, marker);
            }
        }

        m = MONTH_DAY.matcher(lower);
        while (m.find()) {
            LocalDate d = dayInMonth(num(m, 2), monthNumber(m.group(1)), m.group(3), today);
            if (d != null) {
                return done(single(d, today), lower, m, marker);
            }
        }

        m = LAST_N_DAYS.matcher(lower);
        if (m.find()) {
            int n = num(m, 1);
            if (n >= 1 && n <= 366) {
                LocalDate from = today.minusDays(n - 1L);
                return done(new Period("in the last " + n + (n == 1 ? " day" : " days"),
                        range(from, today), from, today), lower, m, marker);
            }
        }

        m = THIS_FY.matcher(lower);
        if (m.find()) {
            LocalDate from = LocalDate.of(fyStartYear(today), 4, 1);
            return done(new Period("this financial year", range(from, today), from, today), lower, m, marker);
        }
        m = LAST_FY.matcher(lower);
        if (m.find()) {
            int startYear = fyStartYear(today) - 1;
            LocalDate from = LocalDate.of(startYear, 4, 1);
            LocalDate to = LocalDate.of(startYear + 1, 3, 31);
            return done(new Period("last financial year", range(from, to), from, to), lower, m, marker);
        }

        m = THIS_WEEK.matcher(lower);
        if (m.find()) {
            LocalDate from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            return done(new Period("this week", range(from, today), from, today), lower, m, marker);
        }
        m = LAST_WEEK.matcher(lower);
        if (m.find()) {
            LocalDate from = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
            LocalDate to = from.plusDays(6);
            return done(new Period("last week", range(from, to), from, to), lower, m, marker);
        }

        m = THIS_MONTH.matcher(lower);
        if (m.find()) {
            LocalDate from = today.withDayOfMonth(1);
            return done(new Period("this month", range(from, today), from, today), lower, m, marker);
        }
        m = LAST_MONTH.matcher(lower);
        if (m.find()) {
            LocalDate from = today.withDayOfMonth(1).minusMonths(1);
            LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
            return done(new Period("last month", range(from, to), from, to), lower, m, marker);
        }

        m = THIS_YEAR.matcher(lower);
        if (m.find()) {
            LocalDate from = LocalDate.of(today.getYear(), 1, 1);
            return done(new Period("this year", range(from, today), from, today), lower, m, marker);
        }
        m = LAST_YEAR.matcher(lower);
        if (m.find()) {
            LocalDate from = LocalDate.of(today.getYear() - 1, 1, 1);
            LocalDate to = LocalDate.of(today.getYear() - 1, 12, 31);
            return done(new Period("last year", range(from, to), from, to), lower, m, marker);
        }

        m = YESTERDAY.matcher(lower);
        if (m.find()) {
            return done(single(today.minusDays(1), today), lower, m, marker);
        }
        m = TODAY.matcher(lower);
        if (m.find()) {
            return done(single(today, today), lower, m, marker);
        }
        m = ALL_TIME.matcher(lower);
        if (m.find()) {
            return done(Period.allTime(), lower, m, marker);
        }

        m = MONTH_ONLY.matcher(lower);
        while (m.find()) {
            String preposition = m.group(1);
            String yearText = m.group(3);
            int month = monthNumber(m.group(2));
            // "may" is also an ordinary verb: only read it as a month when the
            // sentence clearly points at one ("in may", "may 2026").
            if ("may".equals(m.group(2)) && preposition == null && yearText == null) {
                continue;
            }
            int year = yearText != null ? Integer.parseInt(yearText)
                    : (month > today.getMonthValue() ? today.getYear() - 1 : today.getYear());
            LocalDate from = date(year, month, 1);
            if (from == null) {
                continue;
            }
            LocalDate to = from.withDayOfMonth(from.lengthOfMonth());
            return done(new Period("in " + MONTH_YEAR.format(from), range(from, to), from, to), lower, m, marker);
        }

        return new Extraction(null, lower);
    }

    private static Extraction done(Period period, String text, Matcher m, String marker) {
        return new Extraction(period, text.substring(0, m.start()) + marker + text.substring(m.end()));
    }

    /** A one-day period worded relative to today when it is today or yesterday. */
    private static Period single(LocalDate d, LocalDate today) {
        if (d.equals(today)) {
            return new Period("today", DAY.format(d), d, d);
        }
        if (d.equals(today.minusDays(1))) {
            return new Period("yesterday", DAY.format(d), d, d);
        }
        return new Period("on " + DAY.format(d), null, d, d);
    }

    private static String range(LocalDate from, LocalDate to) {
        if (from.equals(to)) {
            return DAY.format(from);
        }
        return (from.getYear() == to.getYear() ? DAY_NO_YEAR.format(from) : DAY.format(from))
                + " \u2013 " + DAY.format(to);
    }

    /** The calendar year in which the current financial year (1 April start) began. */
    private static int fyStartYear(LocalDate today) {
        return today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1;
    }

    /** Day in a named month; a missing year means the most recent such date, never one in the future. */
    private static LocalDate dayInMonth(int day, int month, String yearText, LocalDate today) {
        if (yearText != null) {
            return date(Integer.parseInt(yearText), month, day);
        }
        LocalDate candidate = date(today.getYear(), month, day);
        if (candidate != null && candidate.isAfter(today)) {
            candidate = date(today.getYear() - 1, month, day);
        }
        return candidate;
    }

    private static int monthNumber(String name) {
        return switch (name.substring(0, 3)) {
            case "jan" -> 1;
            case "feb" -> 2;
            case "mar" -> 3;
            case "apr" -> 4;
            case "may" -> 5;
            case "jun" -> 6;
            case "jul" -> 7;
            case "aug" -> 8;
            case "sep" -> 9;
            case "oct" -> 10;
            case "nov" -> 11;
            default -> 12;
        };
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    private static LocalDate date(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            return null;
        }
    }
}
