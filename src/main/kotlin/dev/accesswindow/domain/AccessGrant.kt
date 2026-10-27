package dev.accesswindow.domain

import java.time.Instant
import java.time.LocalDate

/** Who made a decision. Free text until an identity provider sits in front of the app. */
@JvmInline
value class Actor(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "actor must not be blank" }
    }

    companion object {
        val UNSPECIFIED = Actor("unspecified")
    }
}

/** Input for a new grant, already trimmed and syntactically valid. */
data class GrantDraft(
    val email: String,
    val resource: String,
    val role: String,
    val owner: String,
    val reason: String,
    val reviewDate: LocalDate,
)

data class AccessGrant(
    val id: Long?,
    val email: String,
    val resource: String,
    val role: String,
    val owner: String,
    val reason: String,
    val reviewDate: LocalDate,
    val state: GrantState,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Int? = null,
) {
    fun status(
        policy: ReviewPolicy,
        today: LocalDate,
    ): ReviewStatus = policy.statusOf(state, reviewDate, today)

    fun revoke(
        by: Actor,
        note: String?,
        at: Instant,
    ): Decision {
        if (state == GrantState.REVOKED) return Decision.AlreadyRevoked
        val revoked = copy(state = GrantState.REVOKED, updatedAt = at)
        return Decision.Applied(revoked, event(EventType.REVOKED, by, note, at, reviewDate, null))
    }

    /** Renewing re-activates a revoked grant: the owner re-approved it with a new date. */
    fun renew(
        until: LocalDate,
        by: Actor,
        note: String?,
        policy: ReviewPolicy,
        today: LocalDate,
        at: Instant,
    ): Decision =
        when (val check = policy.checkReviewDate(until, today, DatePurpose.RENEWAL)) {
            is ReviewDateCheck.Rejected -> Decision.InvalidDate(check)
            ReviewDateCheck.Accepted -> {
                val renewed = copy(reviewDate = until, state = GrantState.ACTIVE, updatedAt = at)
                Decision.Applied(renewed, event(EventType.RENEWED, by, note, at, reviewDate, until))
            }
        }

    private fun event(
        type: EventType,
        by: Actor,
        note: String?,
        at: Instant,
        previous: LocalDate?,
        next: LocalDate?,
    ) = AccessEvent(
        id = null,
        grantId = checkNotNull(id) { "grant must be persisted before a decision" },
        type = type,
        actor = by.name,
        note = note,
        previousReviewDate = previous,
        newReviewDate = next,
        occurredAt = at,
    )

    companion object {
        fun open(
            draft: GrantDraft,
            policy: ReviewPolicy,
            today: LocalDate,
            at: Instant,
        ): Opening =
            when (val check = policy.checkReviewDate(draft.reviewDate, today, DatePurpose.NEW_GRANT)) {
                is ReviewDateCheck.Rejected -> Opening.InvalidDate(check)
                ReviewDateCheck.Accepted ->
                    Opening.Opened(
                        AccessGrant(
                            id = null,
                            email = draft.email,
                            resource = draft.resource,
                            role = draft.role,
                            owner = draft.owner,
                            reason = draft.reason,
                            reviewDate = draft.reviewDate,
                            state = GrantState.ACTIVE,
                            createdAt = at,
                            updatedAt = at,
                        ),
                    )
            }
    }
}

sealed interface Opening {
    data class Opened(
        val grant: AccessGrant,
    ) : Opening

    data class InvalidDate(
        val check: ReviewDateCheck.Rejected,
    ) : Opening
}

sealed interface Decision {
    data class Applied(
        val grant: AccessGrant,
        val event: AccessEvent,
    ) : Decision

    data object AlreadyRevoked : Decision

    data class InvalidDate(
        val check: ReviewDateCheck.Rejected,
    ) : Decision
}

enum class EventType { CREATED, REVOKED, RENEWED }

/** Append-only audit trail entry. */
data class AccessEvent(
    val id: Long?,
    val grantId: Long,
    val type: EventType,
    val actor: String,
    val note: String?,
    val previousReviewDate: LocalDate?,
    val newReviewDate: LocalDate?,
    val occurredAt: Instant,
) {
    companion object {
        fun created(
            grant: AccessGrant,
            by: Actor,
            note: String?,
        ) = AccessEvent(
            id = null,
            grantId = checkNotNull(grant.id) { "grant must be persisted first" },
            type = EventType.CREATED,
            actor = by.name,
            note = note,
            previousReviewDate = null,
            newReviewDate = grant.reviewDate,
            occurredAt = grant.createdAt,
        )
    }
}

/** Persistence port; implemented outside the domain. */
interface AccessLedger {
    /** Active grants first, then by review date, then by id. */
    fun all(): List<AccessGrant>

    fun find(id: Long): AccessGrant?

    /** Inserts when [AccessGrant.id] is null, otherwise updates with an optimistic version check. */
    fun save(grant: AccessGrant): AccessGrant

    fun append(event: AccessEvent): AccessEvent

    fun history(grantId: Long): List<AccessEvent>

    fun isEmpty(): Boolean
}
