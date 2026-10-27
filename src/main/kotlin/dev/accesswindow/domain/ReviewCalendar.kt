package dev.accesswindow.domain

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Resolves "today" in the workspace zone from an injectable [Clock]. */
class ReviewCalendar(
    private val clock: Clock,
) {
    val zone: ZoneId get() = clock.zone

    fun today(): LocalDate = LocalDate.now(clock)

    fun now(): Instant = clock.instant()
}
