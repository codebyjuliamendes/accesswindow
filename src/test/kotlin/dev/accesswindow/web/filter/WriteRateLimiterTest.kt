package dev.accesswindow.web.filter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class WriteRateLimiterTest {
    private var nanos = 0L
    private val limiter = WriteRateLimiter(capacity = 3, period = Duration.ofMinutes(1)) { nanos }

    @Test
    fun `allows a burst up to capacity then asks the client to wait`() {
        repeat(3) { assertThat(limiter.tryAcquire("10.0.0.1")).isZero() }

        assertThat(limiter.tryAcquire("10.0.0.1")).isEqualTo(20)
    }

    @Test
    fun `refills gradually over the period`() {
        repeat(3) { limiter.tryAcquire("10.0.0.1") }

        nanos += Duration.ofSeconds(20).toNanos()

        assertThat(limiter.tryAcquire("10.0.0.1")).isZero()
        assertThat(limiter.tryAcquire("10.0.0.1")).isPositive()
    }

    @Test
    fun `clients are limited independently`() {
        repeat(3) { limiter.tryAcquire("10.0.0.1") }

        assertThat(limiter.tryAcquire("10.0.0.2")).isZero()
    }
}
