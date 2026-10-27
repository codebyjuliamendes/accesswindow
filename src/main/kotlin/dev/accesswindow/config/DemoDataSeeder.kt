package dev.accesswindow.config

import dev.accesswindow.application.AccessReviewService
import dev.accesswindow.domain.AccessEvent
import dev.accesswindow.domain.AccessGrant
import dev.accesswindow.domain.AccessLedger
import dev.accesswindow.domain.Actor
import dev.accesswindow.domain.GrantDraft
import dev.accesswindow.domain.GrantState
import dev.accesswindow.domain.ReviewCalendar
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/** Demo ledger with review dates relative to today; runs only on an empty database. */
@Component
@ConditionalOnProperty("accesswindow.demo-seed", havingValue = "true")
class DemoDataSeeder(
    private val ledger: AccessLedger,
    private val service: AccessReviewService,
    private val calendar: ReviewCalendar,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (!ledger.isEmpty()) return
        val today = calendar.today()
        val seeder = Actor("demo-seed")
        SAMPLES.forEach { (offset, draft) ->
            val date = today.plusDays(offset)
            if (offset >= 0) {
                service.create(draft.copy(reviewDate = date), seeder)
            } else {
                // Past dates cannot be created through the API; insert the overdue sample directly.
                val now = calendar.now()
                val saved =
                    ledger.save(
                        AccessGrant(
                            id = null,
                            email = draft.email,
                            resource = draft.resource,
                            role = draft.role,
                            owner = draft.owner,
                            reason = draft.reason,
                            reviewDate = date,
                            state = GrantState.ACTIVE,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                ledger.append(AccessEvent.created(saved, seeder, saved.reason))
            }
        }
        log.info("demo.seeded records={}", SAMPLES.size)
    }

    companion object {
        private fun draft(
            email: String,
            resource: String,
            role: String,
            owner: String,
            reason: String,
        ) = GrantDraft(email, resource, role, owner, reason, LocalDate.MIN)

        /** Same records and offsets as the original demo dataset. */
        val SAMPLES: List<Pair<Long, GrantDraft>> =
            listOf(
                14L to draft("alex@northstar.dev", "Production AWS", "Admin", "Morgan Lee", "Incident response support"),
                -2L to draft("sam@studio.co", "Design system", "Editor", "Taylor Kim", "Contract design engagement"),
                34L to draft("pat@dataworks.io", "Analytics warehouse", "Read only", "Morgan Lee", "Quarterly reporting project"),
                67L to draft("devon@agency.test", "GitHub organization", "Member", "Jordan Park", "Website redesign handoff"),
                6L to draft("kim@northstar.dev", "Customer database", "Read only", "Taylor Kim", "Data migration review"),
            )
    }
}
