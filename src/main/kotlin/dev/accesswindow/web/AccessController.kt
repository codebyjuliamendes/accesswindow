package dev.accesswindow.web

import dev.accesswindow.application.AccessReviewService
import dev.accesswindow.application.CreateOutcome
import dev.accesswindow.application.DecisionOutcome
import dev.accesswindow.domain.GrantDraft
import dev.accesswindow.domain.ReviewDateCheck
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException

@RestController
@RequestMapping("/api", produces = [MediaType.APPLICATION_JSON_VALUE])
class AccessController(
    private val service: AccessReviewService,
    private val actors: ActorResolver,
) {
    @GetMapping("/access")
    fun register(): List<AccessRecord> = service.register().map(AccessRecord::from)

    @GetMapping("/access/{id}")
    fun record(
        @PathVariable id: Long,
    ): ResponseEntity<Any> = service.find(id)?.let { ResponseEntity.ok<Any>(AccessRecord.from(it)) } ?: notFound()

    @PostMapping("/access", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @Valid @RequestBody body: CreateAccessRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val draft =
            GrantDraft(
                email =
                    body.email
                        .orEmpty()
                        .trim()
                        .lowercase(),
                resource = body.resource.orEmpty().trim(),
                role = body.role.orEmpty().trim(),
                owner = body.owner.orEmpty().trim(),
                reason = body.reason.orEmpty().trim(),
                reviewDate = parseDate("expiresAt", body.expiresAt.orEmpty()),
            )
        return when (val outcome = service.create(draft, actors.resolve(request, body.actor))) {
            is CreateOutcome.Rejected -> invalidDate("expiresAt", outcome.check)
            is CreateOutcome.Created -> {
                val record = AccessRecord.from(outcome.view)
                ResponseEntity.created(URI.create("/api/access/${record.id}")).body(record)
            }
        }
    }

    @PatchMapping("/access/{id}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun decide(
        @PathVariable id: Long,
        @Valid @RequestBody body: DecisionRequest,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        val actor = actors.resolve(request, body.actor)
        val note = body.note?.trim()?.takeIf { it.isNotEmpty() }
        val outcome =
            when (body.action) {
                "revoke" -> service.revoke(id, actor, note)
                else -> {
                    val date = body.expiresAt?.takeIf { it.isNotBlank() } ?: throw InvalidFieldException("expiresAt", DECISION_MESSAGE)
                    service.renew(id, parseDate("expiresAt", date), actor, note)
                }
            }
        return when (outcome) {
            is DecisionOutcome.Done -> ResponseEntity.ok(DecisionResponse(ok = true, access = AccessRecord.from(outcome.view)))
            DecisionOutcome.NotFound -> notFound()
            DecisionOutcome.AlreadyRevoked ->
                problem(Problems.of(HttpStatus.CONFLICT, "already-revoked", "Already revoked", "This access is already revoked."))
            is DecisionOutcome.Rejected -> invalidDate("expiresAt", outcome.check)
        }
    }

    @GetMapping("/access/{id}/events")
    fun events(
        @PathVariable id: Long,
    ): ResponseEntity<Any> = service.history(id)?.let { history -> ResponseEntity.ok(history.map(EventItem::from)) } ?: notFound()

    @GetMapping("/review-due")
    fun reviewDue(): List<ReviewDueItem> = service.reviewQueue().map(ReviewDueItem::from)

    @GetMapping("/review-due.csv", produces = ["text/csv"])
    fun reviewDueCsv(): ResponseEntity<String> =
        ResponseEntity
            .ok()
            .contentType(MediaType("text", "csv", Charsets.UTF_8))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition
                    .attachment()
                    .filename("access-review-due.csv")
                    .build()
                    .toString(),
            ).body(ReviewCsv.render(service.reviewQueue()))

    @GetMapping("/summary")
    fun summary(): SummaryResponse = SummaryResponse.from(service.summary())

    @GetMapping("/policy")
    fun policy(): PolicyResponse = PolicyResponse.from(service.policy())

    private fun parseDate(
        field: String,
        value: String,
    ): LocalDate =
        try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            throw InvalidFieldException(field, "Use a real calendar date in YYYY-MM-DD format.")
        }

    private fun invalidDate(
        field: String,
        check: ReviewDateCheck.Rejected,
    ): ResponseEntity<Any> = problem(validationProblem(listOf(FieldProblem(field, check.message))))

    private fun notFound(): ResponseEntity<Any> =
        problem(Problems.of(HttpStatus.NOT_FOUND, "not-found", "Not found", "Access record not found."))

    private fun problem(detail: ProblemDetail): ResponseEntity<Any> =
        ResponseEntity.status(detail.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(detail)
}
