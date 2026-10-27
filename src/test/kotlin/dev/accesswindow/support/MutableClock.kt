package dev.accesswindow.support

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Test clock that can be moved between calls. */
class MutableClock(
    @Volatile private var current: Instant,
    private val zoneId: ZoneId,
) : Clock() {
    override fun getZone(): ZoneId = zoneId

    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)

    override fun instant(): Instant = current

    fun set(instant: Instant) {
        current = instant
    }

    fun advance(duration: Duration) {
        current = current.plus(duration)
    }
}
