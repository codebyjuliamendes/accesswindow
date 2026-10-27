package dev.accesswindow.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Review rules for a grant. Calendar arithmetic only: callers pass "today" already
 * resolved in the workspace time zone, so DST and offsets never shift a day count.
 */
data class ReviewPolicy(
    val dueSoonDays: Int = DEFAULT_DUE_SOON_DAYS,
    val maxHorizonDays: Int = DEFAULT_MAX_HORIZON_DAYS,
) {
    init {
        require(dueSoonDays >= 0) { "dueSoonDays must be zero or positive" }
        require(maxHorizonDays > dueSoonDays) { "maxHorizonDays must be greater than dueSoonDays" }
    }

    /** Whole calendar days from [today] to [reviewDate]; negative once the date has passed. */
    fun daysLeft(
        reviewDate: LocalDate,
        today: LocalDate,
    ): Long = ChronoUnit.DAYS.between(today, reviewDate)

    fun statusOf(
        state: GrantState,
        reviewDate: LocalDate,
        today: LocalDate,
    ): ReviewStatus =
        when {
            state == GrantState.REVOKED -> ReviewStatus.REVOKED
            reviewDate.isBefore(today) -> ReviewStatus.OVERDUE
            daysLeft(reviewDate, today) <= dueSoonDays -> ReviewStatus.DUE_SOON
            else -> ReviewStatus.ON_TRACK
        }

    fun latestReviewDate(today: LocalDate): LocalDate = today.plusDays(maxHorizonDays.toLong())

    /** A new grant may be reviewed as early as today; a renewal must move the date past today. */
    fun checkReviewDate(
        date: LocalDate,
        today: LocalDate,
        purpose: DatePurpose,
    ): ReviewDateCheck {
        val earliest = if (purpose == DatePurpose.RENEWAL) today.plusDays(1) else today
        val latest = latestReviewDate(today)
        return when {
            date.isBefore(earliest) -> ReviewDateCheck.TooEarly(earliest)
            date.isAfter(latest) -> ReviewDateCheck.TooLate(latest)
            else -> ReviewDateCheck.Accepted
        }
    }

    companion object {
        const val DEFAULT_DUE_SOON_DAYS = 14
        const val DEFAULT_MAX_HORIZON_DAYS = 365
    }
}

enum class DatePurpose { NEW_GRANT, RENEWAL }

sealed interface ReviewDateCheck {
    data object Accepted : ReviewDateCheck

    sealed interface Rejected : ReviewDateCheck {
        val message: String
    }

    data class TooEarly(
        val earliest: LocalDate,
    ) : Rejected {
        override val message = "Review date must be on or after $earliest."
    }

    data class TooLate(
        val latest: LocalDate,
    ) : Rejected {
        override val message = "Review date must be on or before $latest."
    }
}

enum class GrantState { ACTIVE, REVOKED }

enum class ReviewStatus {
    ON_TRACK,
    DUE_SOON,
    OVERDUE,
    REVOKED,
    ;

    val needsReview: Boolean get() = this == DUE_SOON || this == OVERDUE
}
