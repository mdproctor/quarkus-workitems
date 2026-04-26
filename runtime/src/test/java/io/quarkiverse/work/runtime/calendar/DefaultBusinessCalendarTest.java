package io.quarkiverse.work.runtime.calendar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import io.quarkiverse.work.api.HolidayCalendar;
import io.quarkiverse.work.runtime.config.WorkItemsConfig;

/**
 * Unit tests for DefaultBusinessCalendar — no Quarkus, no CDI.
 *
 * <p>
 * Injects HolidayCalendar directly via a package-visible field rather than
 * going through CDI Instance, keeping the test clean and fast.
 */
class DefaultBusinessCalendarTest {

    static final ZoneId LONDON = ZoneId.of("Europe/London");

    static final Instant MON_10AM = at(2026, 4, 27, 10, 0); // Monday mid-morning
    static final Instant FRI_4PM = at(2026, 4, 24, 16, 0); // Friday 4pm
    static final Instant SAT_10AM = at(2026, 4, 25, 10, 0); // Saturday
    static final Instant MON_9AM = at(2026, 4, 27, 9, 0); // Monday start of day

    static Instant at(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(LONDON).toInstant();
    }

    static DefaultBusinessCalendar calendarWithHoliday(LocalDate holiday) {
        final DefaultBusinessCalendar cal = new DefaultBusinessCalendar(new StubConfig());
        cal.holidayCalendarForTest = (date, zone) -> date.equals(holiday);
        return cal;
    }

    static DefaultBusinessCalendar calendarNoHolidays() {
        final DefaultBusinessCalendar cal = new DefaultBusinessCalendar(new StubConfig());
        cal.holidayCalendarForTest = (date, zone) -> false;
        return cal;
    }

    // ── addBusinessDuration ───────────────────────────────────────────────────

    @Test
    void addBusinessDuration_withinSameDay() {
        // 10:00 + 2h = 12:00 same Monday
        final Instant result = calendarNoHolidays().addBusinessDuration(MON_10AM, Duration.ofHours(2), LONDON);
        final ZonedDateTime zdt = result.atZone(LONDON);
        assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 27));
        assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(12, 0));
    }

    @Test
    void addBusinessDuration_crossesEndOfDay() {
        // Mon 10:00 + 8h: 10→17 = 7h, then 1h into Tue → Tue 10:00
        final Instant result = calendarNoHolidays().addBusinessDuration(MON_10AM, Duration.ofHours(8), LONDON);
        final ZonedDateTime zdt = result.atZone(LONDON);
        assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 28)); // Tuesday
        assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void addBusinessDuration_fromFridayAfternoon_skipsWeekend() {
        // Fri 16:00 + 2h: 1h Fri (16-17), 1h Mon (09-10) → Mon 10:00
        final Instant result = calendarNoHolidays().addBusinessDuration(FRI_4PM, Duration.ofHours(2), LONDON);
        final ZonedDateTime zdt = result.atZone(LONDON);
        assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 27)); // Monday
        assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
    }

    @Test
    void addBusinessDuration_fromSaturday_skipsToMonday() {
        // Start Saturday → first business moment is Mon 09:00 → +2h = Mon 11:00
        final Instant result = calendarNoHolidays().addBusinessDuration(SAT_10AM, Duration.ofHours(2), LONDON);
        final ZonedDateTime zdt = result.atZone(LONDON);
        assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 27)); // Monday
        assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(11, 0));
    }

    @Test
    void addBusinessDuration_zeroHours_returnsSameBusinessMoment() {
        final Instant result = calendarNoHolidays().addBusinessDuration(MON_10AM, Duration.ZERO, LONDON);
        assertThat(result).isEqualTo(MON_10AM);
    }

    @Test
    void addBusinessDuration_crossesHoliday() {
        // Mon is a holiday → first business moment is Tue 09:00 → +1h = Tue 10:00
        final Instant result = calendarWithHoliday(LocalDate.of(2026, 4, 27))
                .addBusinessDuration(MON_10AM, Duration.ofHours(1), LONDON);
        final ZonedDateTime zdt = result.atZone(LONDON);
        assertThat(zdt.toLocalDate()).isEqualTo(LocalDate.of(2026, 4, 28)); // Tuesday
        assertThat(zdt.toLocalTime()).isEqualTo(LocalTime.of(10, 0));
    }

    // ── isBusinessHour ────────────────────────────────────────────────────────

    @Test
    void isBusinessHour_trueAtMidMorning() {
        assertThat(calendarNoHolidays().isBusinessHour(MON_10AM, LONDON)).isTrue();
    }

    @Test
    void isBusinessHour_trueAtDayStart() {
        assertThat(calendarNoHolidays().isBusinessHour(MON_9AM, LONDON)).isTrue();
    }

    @Test
    void isBusinessHour_falseOnWeekend() {
        assertThat(calendarNoHolidays().isBusinessHour(SAT_10AM, LONDON)).isFalse();
    }

    @Test
    void isBusinessHour_falseAfterEndOfDay() {
        final Instant mon6pm = at(2026, 4, 27, 18, 0);
        assertThat(calendarNoHolidays().isBusinessHour(mon6pm, LONDON)).isFalse();
    }

    @Test
    void isBusinessHour_falseBeforeStartOfDay() {
        final Instant mon7am = at(2026, 4, 27, 7, 0);
        assertThat(calendarNoHolidays().isBusinessHour(mon7am, LONDON)).isFalse();
    }

    @Test
    void isBusinessHour_falseOnHoliday() {
        assertThat(calendarWithHoliday(LocalDate.of(2026, 4, 27))
                .isBusinessHour(MON_10AM, LONDON)).isFalse();
    }

    // ── stub ─────────────────────────────────────────────────────────────────

    static class StubConfig implements WorkItemsConfig {
        @Override
        public int defaultExpiryHours() {
            return 24;
        }

        @Override
        public int defaultClaimHours() {
            return 4;
        }

        @Override
        public String escalationPolicy() {
            return "notify";
        }

        @Override
        public String claimEscalationPolicy() {
            return "notify";
        }

        @Override
        public CleanupConfig cleanup() {
            return () -> 60;
        }

        @Override
        public RoutingConfig routing() {
            return () -> "least-loaded";
        }

        @Override
        public BusinessHoursConfig businessHours() {
            return new BusinessHoursConfig() {
                @Override
                public String timezone() {
                    return "Europe/London";
                }

                @Override
                public String start() {
                    return "09:00";
                }

                @Override
                public String end() {
                    return "17:00";
                }

                @Override
                public String workDays() {
                    return "MON,TUE,WED,THU,FRI";
                }

                @Override
                public java.util.Optional<String> holidays() {
                    return java.util.Optional.empty();
                }

                @Override
                public java.util.Optional<String> holidayIcalUrl() {
                    return java.util.Optional.empty();
                }
            };
        }
    }
}
