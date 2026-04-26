package io.quarkiverse.work.runtime.calendar;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import io.quarkiverse.work.api.BusinessCalendar;
import io.quarkiverse.work.api.HolidayCalendar;
import io.quarkiverse.work.runtime.config.WorkItemsConfig;

/**
 * Default {@link BusinessCalendar} implementation driven by
 * {@code quarkus.work.business-hours.*} configuration.
 *
 * <p>
 * Business time is counted only during the configured daily window on configured
 * working days, excluding dates reported as holidays by the active
 * {@link HolidayCalendar}. {@code addBusinessDuration} advances minute-by-minute
 * through the calendar, which is accurate for practical SLA durations (hours to
 * days) without significant performance cost.
 *
 * <p>
 * Override the entire calendar by providing a CDI {@code @ApplicationScoped}
 * bean that implements {@link BusinessCalendar} with {@code @Alternative @Priority(1)}.
 */
@ApplicationScoped
public class DefaultBusinessCalendar implements BusinessCalendar {

    private final ZoneId defaultZone;
    private final LocalTime dayStart;
    private final LocalTime dayEnd;
    private final Set<DayOfWeek> workDays;

    @Inject
    Instance<HolidayCalendar> holidayCalendar;

    /** Test-only override — set directly in unit tests to avoid CDI. */
    HolidayCalendar holidayCalendarForTest;

    @Inject
    public DefaultBusinessCalendar(final WorkItemsConfig config) {
        this.defaultZone = ZoneId.of(config.businessHours().timezone());
        this.dayStart = LocalTime.parse(config.businessHours().start());
        this.dayEnd = LocalTime.parse(config.businessHours().end());
        this.workDays = Arrays.stream(config.businessHours().workDays().split(","))
                .map(String::trim)
                .map(DefaultBusinessCalendar::parseDayOfWeek)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    @Override
    public Instant addBusinessDuration(final Instant start, final Duration businessDuration,
            final ZoneId zone) {
        final ZoneId effectiveZone = zone != null ? zone : defaultZone;
        ZonedDateTime current = start.atZone(effectiveZone);
        long remainingMinutes = businessDuration.toMinutes();

        // Advance to next business moment if start is outside business hours
        current = advanceToNextBusinessMoment(current, effectiveZone);

        while (remainingMinutes > 0) {
            // How many business minutes remain in the current business day window?
            final ZonedDateTime dayEndToday = current.toLocalDate()
                    .atTime(dayEnd).atZone(effectiveZone);
            final long minutesUntilEndOfDay = Duration.between(current, dayEndToday).toMinutes();

            if (minutesUntilEndOfDay >= remainingMinutes) {
                // Finish within this business window
                current = current.plusMinutes(remainingMinutes);
                remainingMinutes = 0;
            } else {
                // Consume the rest of today's window and advance to the next
                remainingMinutes -= minutesUntilEndOfDay;
                current = advanceToNextBusinessMoment(dayEndToday.plusMinutes(1), effectiveZone);
            }
        }

        return current.toInstant();
    }

    @Override
    public boolean isBusinessHour(final Instant instant, final ZoneId zone) {
        final ZoneId effectiveZone = zone != null ? zone : defaultZone;
        final ZonedDateTime zdt = instant.atZone(effectiveZone);
        return isBusinessMoment(zdt, effectiveZone);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private static final java.util.Map<String, DayOfWeek> DAY_ALIASES = java.util.Map.of(
            "MON", DayOfWeek.MONDAY, "TUE", DayOfWeek.TUESDAY, "WED", DayOfWeek.WEDNESDAY,
            "THU", DayOfWeek.THURSDAY, "FRI", DayOfWeek.FRIDAY,
            "SAT", DayOfWeek.SATURDAY, "SUN", DayOfWeek.SUNDAY);

    private static DayOfWeek parseDayOfWeek(final String s) {
        final DayOfWeek aliased = DAY_ALIASES.get(s.toUpperCase());
        return aliased != null ? aliased : DayOfWeek.valueOf(s.toUpperCase());
    }

    private ZonedDateTime advanceToNextBusinessMoment(ZonedDateTime zdt, final ZoneId zone) {
        // If before start of business day, jump to start
        if (zdt.toLocalTime().isBefore(dayStart)) {
            zdt = zdt.toLocalDate().atTime(dayStart).atZone(zone);
        }
        // Walk forward day-by-day until we land on a business day
        while (!isBusinessMoment(zdt, zone)) {
            if (zdt.toLocalTime().isAfter(dayEnd) || zdt.toLocalTime().equals(dayEnd)) {
                // Past end of day — jump to next day's start
                zdt = zdt.toLocalDate().plusDays(1).atTime(dayStart).atZone(zone);
            } else {
                // Not a work day — jump to next day's start
                zdt = zdt.toLocalDate().plusDays(1).atTime(dayStart).atZone(zone);
            }
        }
        return zdt;
    }

    private boolean isBusinessMoment(final ZonedDateTime zdt, final ZoneId zone) {
        if (!workDays.contains(zdt.getDayOfWeek())) {
            return false;
        }
        final LocalDate date = zdt.toLocalDate();
        final HolidayCalendar holidays = holidayCalendarForTest != null ? holidayCalendarForTest
                : (holidayCalendar != null && !holidayCalendar.isUnsatisfied() ? holidayCalendar.get() : null);
        if (holidays != null && holidays.isHoliday(date, zone)) {
            return false;
        }
        final LocalTime time = zdt.toLocalTime();
        return !time.isBefore(dayStart) && time.isBefore(dayEnd);
    }
}
