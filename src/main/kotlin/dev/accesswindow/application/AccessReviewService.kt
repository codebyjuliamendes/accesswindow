package dev.accesswindow.application

import dev.accesswindow.domain.AccessEvent
import dev.accesswindow.domain.AccessGrant
import dev.accesswindow.domain.AccessLedger
import dev.accesswindow.domain.Actor
import dev.accesswindow.domain.Decision
import dev.accesswindow.domain.GrantDraft
import dev.accesswindow.domain.Opening
import dev.accesswindow.domain.ReviewCalendar
import dev.accesswindow.domain.ReviewDateCheck
import dev.accesswindow.domain.ReviewPolicy
import dev.accesswindow.domain.ReviewStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** A grant evaluated against "today". */
data class GrantView(
    val grant: AccessGrant,
    val daysLeft: Long,
    val status: ReviewStatus,
)

data class Summary(
    val total: Int,
    val expiring: Int,
    val overdue: Int,
    val resources: Int,
)

data class PolicySnapshot(
    val today: LocalDate,
    val timeZone: String,
    val dueSoonDays: Int,
    val maxReviewDate: LocalDate,
)

sealed interface CreateOutcome {
    data class Created(
        val view: GrantView,
    ) : CreateOutcome

    data class Rejected(
        val check: ReviewDateCheck.Rejected,
    ) : CreateOutcome
}

sealed interface DecisionOutcome {
    data class Done(
        val view: GrantView,
    ) : DecisionOutcome

    data object NotFound : DecisionOutcome

    data object AlreadyRevoked : DecisionOutcome

    data class Rejected(
        val check: ReviewDateCheck.Rejected,
    ) : DecisionOutcome
}

@Service
class AccessReviewService(
    private val ledger: AccessLedger,
    private val policy: ReviewPolicy,
    private val calendar: ReviewCalendar,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun register(): List<GrantView> {
        val today = calendar.today()
        return ledger.all().map { view(it, today) }
    }

    @Transactional(readOnly = true)
    fun find(id: Long): GrantView? = ledger.find(id)?.let { view(it, calendar.today()) }

    @Transactional(readOnly = true)
    fun reviewQueue(): List<GrantView> = register().filter { it.status.needsReview }

    @Transactional(readOnly = true)
    fun summary(): Summary {
        val active = register().filter { it.status != ReviewStatus.REVOKED }
        return Summary(
            total = active.size,
            expiring = active.count { it.status == ReviewStatus.DUE_SOON },
            overdue = active.count { it.status == ReviewStatus.OVERDUE },
            resources = active.map { it.grant.resource }.distinct().size,
        )
    }

    fun policy(): PolicySnapshot {
        val today = calendar.today()
        return PolicySnapshot(today, calendar.zone.id, policy.dueSoonDays, policy.latestReviewDate(today))
    }

    @Transactional
    fun create(
        draft: GrantDraft,
        by: Actor,
    ): CreateOutcome {
        val today = calendar.today()
        return when (val opening = AccessGrant.open(draft, policy, today, calendar.now())) {
            is Opening.InvalidDate -> CreateOutcome.Rejected(opening.check)
            is Opening.Opened -> {
                val saved = ledger.save(opening.grant)
                ledger.append(AccessEvent.created(saved, by, saved.reason))
                log.info("access.created id={} resource={} actor={}", saved.id, saved.resource, by.name)
                CreateOutcome.Created(view(saved, today))
            }
        }
    }

    @Transactional
    fun revoke(
        id: Long,
        by: Actor,
        note: String?,
    ): DecisionOutcome = decide(id) { it.revoke(by, note, calendar.now()) }

    @Transactional
    fun renew(
        id: Long,
        until: LocalDate,
        by: Actor,
        note: String?,
    ): DecisionOutcome = decide(id) { it.renew(until, by, note, policy, calendar.today(), calendar.now()) }

    /** Null when the grant does not exist. */
    @Transactional(readOnly = true)
    fun history(id: Long): List<AccessEvent>? = ledger.find(id)?.let { ledger.history(id) }

    private fun decide(
        id: Long,
        apply: (AccessGrant) -> Decision,
    ): DecisionOutcome {
        val grant = ledger.find(id) ?: return DecisionOutcome.NotFound
        return when (val decision = apply(grant)) {
            Decision.AlreadyRevoked -> DecisionOutcome.AlreadyRevoked
            is Decision.InvalidDate -> DecisionOutcome.Rejected(decision.check)
            is Decision.Applied -> {
                val saved = ledger.save(decision.grant)
                ledger.append(decision.event)
                log.info(
                    "access.{} id={} actor={}",
                    decision.event.type.name
                        .lowercase(),
                    id,
                    decision.event.actor,
                )
                DecisionOutcome.Done(view(saved, calendar.today()))
            }
        }
    }

    private fun view(
        grant: AccessGrant,
        today: LocalDate,
    ) = GrantView(grant, policy.daysLeft(grant.reviewDate, today), grant.status(policy, today))
}
