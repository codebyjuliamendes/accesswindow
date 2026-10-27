package dev.accesswindow.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate

class ReviewPolicyTest {
    private val policy = ReviewPolicy()
    private val today = LocalDate.of(2024, 3, 15)

    @Nested
    inner class PortedFromOriginalSuite {
        // Offsets of the original demo dataset: the review queue holds overdue and next-14-day grants only.
        @Test
        fun `review queue includes active overdue and due-soon access only`() {
            val offsets = listOf(14L, -2L, 34L, 67L, 6L)
            val queued =
                offsets.filter {
                    policy.statusOf(GrantState.ACTIVE, today.plusDays(it), today).needsReview
                }

            assertThat(queued).containsExactly(14L, -2L, 6L)
            assertThat(queued.map { policy.daysLeft(today.plusDays(it), today) }).allMatch { it <= 14 }
            assertThat(queued.map { policy.daysLeft(today.plusDays(it), today) }).anyMatch { it < 0 }
        }
    }

    @ParameterizedTest(name = "{0} days left -> {1}")
    @CsvSource(
        "-30, OVERDUE",
        "-1, OVERDUE",
        "0, DUE_SOON",
        "1, DUE_SOON",
        "14, DUE_SOON",
        "15, ON_TRACK",
        "365, ON_TRACK",
    )
    fun `status follows the days left boundaries`(
        offset: Long,
        expected: ReviewStatus,
    ) {
        assertThat(policy.statusOf(GrantState.ACTIVE, today.plusDays(offset), today)).isEqualTo(expected)
    }

    @ParameterizedTest
    @CsvSource("-5", "0", "40")
    fun `revoked wins over any date`(offset: Long) {
        val status = policy.statusOf(GrantState.REVOKED, today.plusDays(offset), today)

        assertThat(status).isEqualTo(ReviewStatus.REVOKED)
        assertThat(status.needsReview).isFalse()
    }

    @Test
    fun `due-soon window is configurable`() {
        val strict = ReviewPolicy(dueSoonDays = 3)

        assertThat(strict.statusOf(GrantState.ACTIVE, today.plusDays(3), today)).isEqualTo(ReviewStatus.DUE_SOON)
        assertThat(strict.statusOf(GrantState.ACTIVE, today.plusDays(4), today)).isEqualTo(ReviewStatus.ON_TRACK)
    }

    @Nested
    inner class CalendarEdges {
        @ParameterizedTest(name = "{0} -> {1} = {2} days")
        @CsvSource(
            // leap year: 29 February exists
            "2024-02-28, 2024-03-01, 2",
            "2024-02-29, 2024-03-01, 1",
            // common year
            "2023-02-28, 2023-03-01, 1",
            // end of month and year boundaries
            "2024-01-31, 2024-02-01, 1",
            "2024-04-30, 2024-05-31, 31",
            "2024-12-31, 2025-01-01, 1",
            "2025-01-01, 2024-12-31, -1",
            // whole leap year vs common year
            "2024-01-01, 2025-01-01, 366",
            "2025-01-01, 2026-01-01, 365",
            // DST changes do not alter calendar day counts
            "2024-03-09, 2024-03-11, 2",
            "2024-11-02, 2024-11-04, 2",
        )
        fun `days left uses calendar days`(
            from: LocalDate,
            to: LocalDate,
            expected: Long,
        ) {
            assertThat(policy.daysLeft(to, from)).isEqualTo(expected)
        }

        @Test
        fun `29 February review date becomes overdue on 1 March`() {
            val leapDay = LocalDate.of(2024, 2, 29)

            assertThat(policy.statusOf(GrantState.ACTIVE, leapDay, leapDay)).isEqualTo(ReviewStatus.DUE_SOON)
            assertThat(policy.statusOf(GrantState.ACTIVE, leapDay, LocalDate.of(2024, 3, 1))).isEqualTo(ReviewStatus.OVERDUE)
        }
    }

    @Nested
    inner class ReviewDateRules {
        @Test
        fun `new grant may be reviewed today but not in the past`() {
            assertThat(policy.checkReviewDate(today, today, DatePurpose.NEW_GRANT)).isEqualTo(ReviewDateCheck.Accepted)
            assertThat(policy.checkReviewDate(today.minusDays(1), today, DatePurpose.NEW_GRANT))
                .isEqualTo(ReviewDateCheck.TooEarly(today))
        }

        @Test
        fun `renewal must move the date past today`() {
            assertThat(policy.checkReviewDate(today, today, DatePurpose.RENEWAL))
                .isEqualTo(ReviewDateCheck.TooEarly(today.plusDays(1)))
            assertThat(policy.checkReviewDate(today.plusDays(1), today, DatePurpose.RENEWAL)).isEqualTo(ReviewDateCheck.Accepted)
        }

        @Test
        fun `dates beyond the horizon are rejected`() {
            val latest = today.plusDays(365)

            assertThat(policy.latestReviewDate(today)).isEqualTo(latest)
            assertThat(policy.checkReviewDate(latest, today, DatePurpose.RENEWAL)).isEqualTo(ReviewDateCheck.Accepted)
            assertThat(policy.checkReviewDate(latest.plusDays(1), today, DatePurpose.NEW_GRANT))
                .isEqualTo(ReviewDateCheck.TooLate(latest))
        }

        @Test
        fun `rejections explain the allowed date`() {
            assertThat(ReviewDateCheck.TooEarly(today).message).isEqualTo("Review date must be on or after 2024-03-15.")
            assertThat(ReviewDateCheck.TooLate(today).message).isEqualTo("Review date must be on or before 2024-03-15.")
        }
    }

    @Test
    fun `invalid policies fail fast`() {
        assertThatThrownBy { ReviewPolicy(dueSoonDays = -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ReviewPolicy(dueSoonDays = 30, maxHorizonDays = 30) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
