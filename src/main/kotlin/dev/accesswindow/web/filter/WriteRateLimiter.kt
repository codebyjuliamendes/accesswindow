package dev.accesswindow.web.filter

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * In-memory token bucket per client key. Good enough for a single instance; a
 * multi-instance deployment should rate limit at the proxy or with a shared store.
 */
class WriteRateLimiter(
    private val capacity: Int,
    period: Duration,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val nanosPerToken = period.toNanos().toDouble() / capacity
    private val buckets = ConcurrentHashMap<String, Bucket>()

    private class Bucket(
        var tokens: Double,
        var refilledAt: Long,
    )

    /** Returns 0 when the request may proceed, otherwise the seconds to wait. */
    fun tryAcquire(key: String): Long {
        if (buckets.size > MAX_TRACKED_CLIENTS) evictIdle()
        var waitSeconds = 0L
        buckets.compute(key) { _, existing ->
            val now = nanoTime()
            val bucket = existing ?: Bucket(capacity.toDouble(), now)
            refill(bucket, now)
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
            } else {
                waitSeconds = ceil((1.0 - bucket.tokens) * nanosPerToken / NANOS_PER_SECOND).toLong().coerceAtLeast(1)
            }
            bucket
        }
        return waitSeconds
    }

    private fun refill(
        bucket: Bucket,
        now: Long,
    ) {
        val elapsed = now - bucket.refilledAt
        if (elapsed > 0) {
            bucket.tokens = min(capacity.toDouble(), bucket.tokens + elapsed / nanosPerToken)
            bucket.refilledAt = now
        }
    }

    private fun evictIdle() {
        val now = nanoTime()
        // computeIfPresent keeps refill and removal atomic per key.
        buckets.keys.forEach { key ->
            buckets.computeIfPresent(key) { _, bucket ->
                refill(bucket, now)
                bucket.takeIf { it.tokens < capacity }
            }
        }
    }

    companion object {
        private const val MAX_TRACKED_CLIENTS = 10_000
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
