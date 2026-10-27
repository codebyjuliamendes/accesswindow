package dev.accesswindow.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class ReviewCalendarTest {
    private val policy = ReviewPolicy()

    private fun calendarAt(
        instant: String,
        zone: String,
    ) = ReviewCalendar(Clock.fixed(Instant.parse(instant), ZoneId.of(zone)))

    @Test
    fun `today depends on the configured zone`() {
        val instant = "2024-03-01T02:30:00Z"

        assertThat(calendarAt(instant, "UTC").today()).isEqualTo(LocalDate.of(2024, 3, 1))
        assertThat(calendarAt(instant, "America/Sao_Paulo").today()).isEqualTo(LocalDate.of(2024, 2, 29))
        assertThat(calendarAt(instant, "Pacific/Kiritimati").today()).isEqualTo(LocalDate.of(2024, 3, 1))
    }

    @Test
    fun `the same instant can be due today in one zone and overdue in another`() {
        val reviewDate = LocalDate.of(2024, 12, 31)
        val instant = "2025-01-01T01:00:00Z"

        val inSaoPaulo = calendarAt(instant, "America/Sao_Paulo").today()
        val inUtc = calendarAt(instant, "UTC").today()

        assertThat(policy.statusOf(GrantState.ACTIVE, reviewDate, inSaoPaulo)).isEqualTo(ReviewStatus.DUE_SOON)
        assertThat(policy.statusOf(GrantState.ACTIVE, reviewDate, inUtc)).isEqualTo(ReviewStatus.OVERDUE)
    }

    @Test
    fun `day count is stable across a DST change`() {
        // New York springs forward on 2024-03-10; the 23-hour day still counts as one day.
        val calendar = calendarAt("2024-03-10T06:30:00Z", "America/New_York")

        assertThat(calendar.today()).isEqualTo(LocalDate.of(2024, 3, 10))
        assertThat(policy.daysLeft(LocalDate.of(2024, 3, 11), calendar.today())).isEqualTo(1)
        assertThat(calendar.zone).isEqualTo(ZoneId.of("America/New_York"))
    }
}
