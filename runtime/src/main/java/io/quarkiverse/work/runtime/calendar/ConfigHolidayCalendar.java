package io.quarkiverse.work.runtime.calendar;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkiverse.work.api.HolidayCalendar;
import io.quarkiverse.work.runtime.config.WorkItemsConfig;

/**
 * Default {@link HolidayCalendar} — reads a static list of dates from
 * {@code quarkus.work.business-hours.holidays} (comma-separated {@code YYYY-MM-DD}).
 *
 * <p>
 * Override by providing your own CDI {@code @ApplicationScoped} bean that
 * implements {@link HolidayCalendar} and annotating it with
 * {@code @Alternative @Priority(1)}.
 */
@ApplicationScoped
public class ConfigHolidayCalendar implements HolidayCalendar {

    private final Set<LocalDate> holidays;

    @Inject
    public ConfigHolidayCalendar(final WorkItemsConfig config) {
        final java.util.Optional<String> rawOpt = config.businessHours().holidays();
        final String raw = rawOpt.orElse("");
        if (raw == null || raw.isBlank()) {
            this.holidays = Set.of();
        } else {
            this.holidays = Stream.of(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isBlank())
                    .map(LocalDate::parse)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    @Override
    public boolean isHoliday(final LocalDate date, final ZoneId zone) {
        return holidays.contains(date);
    }
}
