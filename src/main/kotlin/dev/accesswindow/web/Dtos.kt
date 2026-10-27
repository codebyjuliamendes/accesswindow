package dev.accesswindow.web

import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.annotation.JsonNaming
import dev.accesswindow.application.GrantView
import dev.accesswindow.application.PolicySnapshot
import dev.accesswindow.application.Summary
import dev.accesswindow.domain.AccessEvent
import dev.accesswindow.domain.GrantState
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant
import java.time.LocalDate

private const val SINGLE_LINE = "^[^\\p{Cntrl}]*$"
private const val ISO_DATE = "^$|^\\d{4}-\\d{2}-\\d{2}$"
private const val EMAIL_WITH_DOMAIN = "^$|^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"
private const val DATE_MESSAGE = "Use a date in YYYY-MM-DD format."
private const val TEXT_MESSAGE = "Use a single line without control characters."
const val DECISION_MESSAGE = "Use revoke or renew with an expiry date."

data class CreateAccessRequest(
    @field:NotBlank(message = "Email is required.")
    @field:Size(max = 254, message = "Email must be at most 254 characters.")
    @field:Email(message = "Enter a valid email address.")
    @field:Pattern(regexp = EMAIL_WITH_DOMAIN, message = "Enter a valid email address.")
    val email: String?,
    @field:NotBlank(message = "Resource is required.")
    @field:Size(max = 120, message = "Resource must be at most 120 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val resource: String?,
    @field:NotBlank(message = "Role is required.")
    @field:Size(max = 60, message = "Role must be at most 60 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val role: String?,
    @field:NotBlank(message = "Owner is required.")
    @field:Size(max = 120, message = "Owner must be at most 120 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val owner: String?,
    @field:NotBlank(message = "Reason is required.")
    @field:Size(max = 500, message = "Reason must be at most 500 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val reason: String?,
    @field:NotBlank(message = "Review date is required.")
    @field:Pattern(regexp = ISO_DATE, message = DATE_MESSAGE)
    val expiresAt: String?,
    @field:Size(max = 120, message = "Name must be at most 120 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val actor: String? = null,
)

data class DecisionRequest(
    @field:NotBlank(message = DECISION_MESSAGE)
    @field:Pattern(regexp = "^$|^(revoke|renew)$", message = DECISION_MESSAGE)
    val action: String?,
    @field:Pattern(regexp = ISO_DATE, message = DATE_MESSAGE)
    val expiresAt: String? = null,
    @field:Size(max = 120, message = "Name must be at most 120 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val actor: String? = null,
    @field:Size(max = 500, message = "Note must be at most 500 characters.")
    @field:Pattern(regexp = SINGLE_LINE, message = TEXT_MESSAGE)
    val note: String? = null,
)

/** Register row; snake_case keeps the original `/api/access` contract. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class AccessRecord(
    val id: Long,
    val email: String,
    val resource: String,
    val role: String,
    val owner: String,
    val reason: String,
    val expiresAt: LocalDate,
    val status: String,
    val reviewStatus: String,
    val daysLeft: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun from(view: GrantView) =
            with(view.grant) {
                AccessRecord(
                    id = checkNotNull(id),
                    email = email,
                    resource = resource,
                    role = role,
                    owner = owner,
                    reason = reason,
                    expiresAt = reviewDate,
                    status = if (state == GrantState.REVOKED) "revoked" else "active",
                    reviewStatus = view.status.name.lowercase(),
                    daysLeft = view.daysLeft,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                )
            }
    }
}

/** Review queue row; camelCase as in the original `/api/review-due`. */
data class ReviewDueItem(
    val id: Long,
    val email: String,
    val resource: String,
    val role: String,
    val owner: String,
    val reason: String,
    val expiresAt: LocalDate,
    val daysLeft: Long,
    val reviewStatus: String,
) {
    companion object {
        fun from(view: GrantView) =
            with(view.grant) {
                ReviewDueItem(
                    checkNotNull(id),
                    email,
                    resource,
                    role,
                    owner,
                    reason,
                    reviewDate,
                    view.daysLeft,
                    view.status.name.lowercase(),
                )
            }
    }
}

data class DecisionResponse(
    val ok: Boolean,
    val access: AccessRecord,
)

data class EventItem(
    val id: Long,
    val type: String,
    val actor: String,
    val note: String?,
    val previousExpiresAt: LocalDate?,
    val newExpiresAt: LocalDate?,
    val occurredAt: Instant,
) {
    companion object {
        fun from(event: AccessEvent) =
            EventItem(
                id = checkNotNull(event.id),
                type = event.type.name.lowercase(),
                actor = event.actor,
                note = event.note,
                previousExpiresAt = event.previousReviewDate,
                newExpiresAt = event.newReviewDate,
                occurredAt = event.occurredAt,
            )
    }
}

data class SummaryResponse(
    val total: Int,
    val expiring: Int,
    val overdue: Int,
    val resources: Int,
) {
    companion object {
        fun from(summary: Summary) = SummaryResponse(summary.total, summary.expiring, summary.overdue, summary.resources)
    }
}

data class PolicyResponse(
    val today: LocalDate,
    val timeZone: String,
    val dueSoonDays: Int,
    val maxReviewDate: LocalDate,
) {
    companion object {
        fun from(policy: PolicySnapshot) = PolicyResponse(policy.today, policy.timeZone, policy.dueSoonDays, policy.maxReviewDate)
    }
}
