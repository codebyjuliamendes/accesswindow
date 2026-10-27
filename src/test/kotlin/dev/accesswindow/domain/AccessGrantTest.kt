package dev.accesswindow.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class AccessGrantTest {
    private val policy = ReviewPolicy()
    private val today = LocalDate.of(2024, 3, 15)
    private val now = Instant.parse("2024-03-15T12:00:00Z")
    private val morgan = Actor("Morgan Lee")

    private val draft = GrantDraft("sam@studio.co", "Design system", "Editor", "Taylor Kim", "Contract work", today.plusDays(30))

    private fun persisted(
        state: GrantState = GrantState.ACTIVE,
        reviewDate: LocalDate = today.plusDays(5),
    ) = AccessGrant(
        id = 7,
        email = "sam@studio.co",
        resource = "Design system",
        role = "Editor",
        owner = "Taylor Kim",
        reason = "Contract work",
        reviewDate = reviewDate,
        state = state,
        createdAt = now.minusSeconds(86_400),
        updatedAt = now.minusSeconds(86_400),
        version = 2,
    )

    @Test
    fun `opening a grant starts it active`() {
        val opening = AccessGrant.open(draft, policy, today, now)

        assertThat(opening).isInstanceOf(Opening.Opened::class.java)
        val grant = (opening as Opening.Opened).grant
        assertThat(grant.state).isEqualTo(GrantState.ACTIVE)
        assertThat(grant.createdAt).isEqualTo(now)
        assertThat(grant.status(policy, today)).isEqualTo(ReviewStatus.ON_TRACK)
    }

    @Test
    fun `opening a grant with a past review date is rejected`() {
        val opening = AccessGrant.open(draft.copy(reviewDate = today.minusDays(1)), policy, today, now)

        assertThat(opening).isEqualTo(Opening.InvalidDate(ReviewDateCheck.TooEarly(today)))
    }

    @Test
    fun `revoking records who, why and the date that was in force`() {
        val decision = persisted().revoke(morgan, "Engagement ended", now)

        assertThat(decision).isInstanceOf(Decision.Applied::class.java)
        val applied = decision as Decision.Applied
        assertThat(applied.grant.state).isEqualTo(GrantState.REVOKED)
        assertThat(applied.grant.updatedAt).isEqualTo(now)
        assertThat(applied.grant.version).isEqualTo(2)
        assertThat(applied.event)
            .isEqualTo(AccessEvent(null, 7, EventType.REVOKED, "Morgan Lee", "Engagement ended", today.plusDays(5), null, now))
    }

    @Test
    fun `revoking twice is rejected`() {
        assertThat(persisted(GrantState.REVOKED).revoke(morgan, null, now)).isEqualTo(Decision.AlreadyRevoked)
    }

    @Test
    fun `renewing moves the review date and keeps the previous one in the trail`() {
        val overdue = persisted(reviewDate = today.minusDays(2))
        val until = today.plusDays(90)

        val applied = overdue.renew(until, morgan, "Quarterly review", policy, today, now) as Decision.Applied

        assertThat(applied.grant.reviewDate).isEqualTo(until)
        assertThat(applied.grant.status(policy, today)).isEqualTo(ReviewStatus.ON_TRACK)
        assertThat(applied.event.type).isEqualTo(EventType.RENEWED)
        assertThat(applied.event.previousReviewDate).isEqualTo(today.minusDays(2))
        assertThat(applied.event.newReviewDate).isEqualTo(until)
    }

    @Test
    fun `renewing a revoked grant re-activates it`() {
        val applied = persisted(GrantState.REVOKED).renew(today.plusDays(30), morgan, null, policy, today, now) as Decision.Applied

        assertThat(applied.grant.state).isEqualTo(GrantState.ACTIVE)
    }

    @Test
    fun `renewing to today or beyond the horizon is rejected`() {
        val grant = persisted()

        assertThat(grant.renew(today, morgan, null, policy, today, now))
            .isEqualTo(Decision.InvalidDate(ReviewDateCheck.TooEarly(today.plusDays(1))))
        assertThat(grant.renew(today.plusDays(400), morgan, null, policy, today, now))
            .isEqualTo(Decision.InvalidDate(ReviewDateCheck.TooLate(today.plusDays(365))))
    }

    @Test
    fun `decisions need a persisted grant`() {
        assertThatThrownBy { persisted().copy(id = null).revoke(morgan, null, now) }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `created event mirrors the grant`() {
        val event = AccessEvent.created(persisted(), morgan, "Contract work")

        assertThat(event.type).isEqualTo(EventType.CREATED)
        assertThat(event.newReviewDate).isEqualTo(today.plusDays(5))
        assertThat(event.occurredAt).isEqualTo(now.minusSeconds(86_400))
    }

    @Test
    fun `actor must not be blank`() {
        assertThatThrownBy { Actor(" ") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
