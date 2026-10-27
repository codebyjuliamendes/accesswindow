package dev.accesswindow.persistence

import dev.accesswindow.domain.AccessEvent
import dev.accesswindow.domain.AccessGrant
import dev.accesswindow.domain.AccessLedger
import dev.accesswindow.domain.EventType
import dev.accesswindow.domain.GrantState
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.relational.core.mapping.Table
import org.springframework.data.repository.CrudRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

@Table("access_grant")
data class GrantRow(
    @Id val id: Long?,
    val email: String,
    val resource: String,
    val role: String,
    val owner: String,
    val reason: String,
    val expiresAt: LocalDate,
    val state: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    @Version val version: Int?,
)

@Table("access_event")
data class EventRow(
    @Id val id: Long?,
    val grantId: Long,
    val type: String,
    val actor: String,
    val note: String?,
    val previousExpiresAt: LocalDate?,
    val newExpiresAt: LocalDate?,
    val occurredAt: Instant,
)

interface GrantRows : CrudRepository<GrantRow, Long> {
    @Query("SELECT * FROM access_grant ORDER BY CASE WHEN state = 'ACTIVE' THEN 0 ELSE 1 END, expires_at, id")
    fun findAllInReviewOrder(): List<GrantRow>
}

interface EventRows : CrudRepository<EventRow, Long> {
    fun findByGrantIdOrderByOccurredAtAscIdAsc(grantId: Long): List<EventRow>
}

@Repository
class JdbcAccessLedger(
    private val grants: GrantRows,
    private val events: EventRows,
) : AccessLedger {
    override fun all(): List<AccessGrant> = grants.findAllInReviewOrder().map { it.toDomain() }

    override fun find(id: Long): AccessGrant? = grants.findByIdOrNull(id)?.toDomain()

    override fun save(grant: AccessGrant): AccessGrant = grants.save(grant.toRow()).toDomain()

    override fun append(event: AccessEvent): AccessEvent = events.save(event.toRow()).toDomain()

    override fun history(grantId: Long): List<AccessEvent> = events.findByGrantIdOrderByOccurredAtAscIdAsc(grantId).map { it.toDomain() }

    override fun isEmpty(): Boolean = grants.count() == 0L
}

private fun GrantRow.toDomain() =
    AccessGrant(
        id = id,
        email = email,
        resource = resource,
        role = role,
        owner = owner,
        reason = reason,
        reviewDate = expiresAt,
        state = GrantState.valueOf(state),
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
    )

private fun AccessGrant.toRow() =
    GrantRow(
        id = id,
        email = email,
        resource = resource,
        role = role,
        owner = owner,
        reason = reason,
        expiresAt = reviewDate,
        state = state.name,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
    )

private fun EventRow.toDomain() =
    AccessEvent(
        id = id,
        grantId = grantId,
        type = EventType.valueOf(type),
        actor = actor,
        note = note,
        previousReviewDate = previousExpiresAt,
        newReviewDate = newExpiresAt,
        occurredAt = occurredAt,
    )

private fun AccessEvent.toRow() =
    EventRow(
        id = id,
        grantId = grantId,
        type = type.name,
        actor = actor,
        note = note,
        previousExpiresAt = previousReviewDate,
        newExpiresAt = newReviewDate,
        occurredAt = occurredAt,
    )
