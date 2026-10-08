package com.shifa.oms.gst.filing;

import com.shifa.oms.gst.filing.FilingStatusService.PeriodFilingStatus;
import com.shifa.oms.gst.filing.domain.CalendarEntry;
import com.shifa.oms.gst.filing.domain.FilingCalendarLogic;
import com.shifa.oms.gst.filing.domain.FilingStatus;
import com.shifa.oms.gst.filing.domain.ReminderWindow;
import com.shifa.oms.gst.filing.domain.ReturnPeriod;
import com.shifa.oms.gst.filing.domain.ReturnType;
import com.shifa.oms.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the GST filing calendar for an Indian financial year (GST returns &amp; filing, Reqs 4.1–4.6).
 *
 * <p>For each calendar month of the Indian financial year (April {@code fyYear} through March
 * {@code fyYear + 1}) and each {@link ReturnType} (GSTR-1 and GSTR-3B), {@link #calendar(int)}
 * combines:
 * <ul>
 *   <li>the current {@link FilingStatus} of that return type for the month, from
 *       {@link FilingStatusService#status(int, int)} (Req 4.2); and</li>
 *   <li>the pure {@link FilingCalendarLogic} calendar math over {@code LocalDate.now(clock)} and the
 *       configured {@link ReminderWindow}, which derives the statutory due date, the reminder /
 *       overdue / due-today flags, and the overdue-day count (Reqs 4.1, 4.3, 4.4, 4.5).</li>
 * </ul>
 * into a {@link CalendarEntry} that presents the {@link FilingStatus} alongside each due date.
 *
 * <p>The reminder window is resolved from {@code app_settings.gst_reminder_window_days} via
 * {@link ReminderWindow#of(Integer)}, which clamps it to {@code [1, 30]} and defaults to {@code 7}
 * when unset or out of range (Req 4.6).
 *
 * <p>Read-only and advisory: it never posts to the ledger and never mutates filing state. It follows
 * the {@code Clock} dual-constructor convention — the primary {@code @Autowired} constructor uses the
 * Asia/Kolkata system clock; a package-private constructor accepts a fixed {@link Clock} for tests.
 */
@Service
@Transactional(readOnly = true)
public class FilingCalendarService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    /** The first calendar month (April) of the Indian financial year. */
    private static final int FY_START_MONTH = 4;

    /** The number of months in a financial year. */
    private static final int MONTHS_IN_YEAR = 12;

    private final FilingStatusService filingStatusService;
    private final SettingsService settingsService;
    private final Clock clock;

    @Autowired
    public FilingCalendarService(FilingStatusService filingStatusService,
                                 SettingsService settingsService) {
        this(filingStatusService, settingsService, Clock.system(ZONE));
    }

    FilingCalendarService(FilingStatusService filingStatusService,
                          SettingsService settingsService,
                          Clock clock) {
        this.filingStatusService = filingStatusService;
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /**
     * Builds the filing calendar for an Indian financial year (Reqs 4.1–4.6).
     *
     * <p>Produces one {@link CalendarEntry} per month × {@link ReturnType} across the financial year
     * that starts in April {@code fyYear} and ends in March {@code fyYear + 1} — 24 entries, ordered by
     * month (April first) and then GSTR-1 before GSTR-3B within each month.
     *
     * @param fyYear the calendar year in which the financial year starts (e.g. {@code 2025} for
     *               FY 2025-26, covering April 2025 through March 2026)
     * @return the calendar entries, each presenting the current filing status alongside its due date,
     *         reminder / overdue / due-today flags
     */
    public List<CalendarEntry> calendar(int fyYear) {
        LocalDate today = LocalDate.now(clock);
        ReminderWindow window = resolveReminderWindow();

        List<CalendarEntry> entries = new ArrayList<>(MONTHS_IN_YEAR * ReturnType.values().length);
        YearMonth month = YearMonth.of(fyYear, FY_START_MONTH);
        for (int i = 0; i < MONTHS_IN_YEAR; i++) {
            ReturnPeriod period = new ReturnPeriod(month.getMonthValue(), month.getYear());
            PeriodFilingStatus statuses = filingStatusService.status(period.month(), period.year());

            entries.add(FilingCalendarLogic.entry(
                    today, period, ReturnType.GSTR1, statuses.gstr1(), window));
            entries.add(FilingCalendarLogic.entry(
                    today, period, ReturnType.GSTR3B, statuses.gstr3b(), window));

            month = month.plusMonths(1);
        }
        return entries;
    }

    /**
     * Resolves the configured reminder window from settings, clamped to {@code [1, 30]} and defaulting
     * to {@code 7} when unset or out of range (Req 4.6).
     */
    private ReminderWindow resolveReminderWindow() {
        Integer configured = settingsService.getSettings().getGstReminderWindowDays();
        return ReminderWindow.of(configured);
    }
}
