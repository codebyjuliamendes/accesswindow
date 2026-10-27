package dev.accesswindow.config

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration
import java.time.ZoneId

@Validated
@ConfigurationProperties("accesswindow")
data class AccessWindowProperties(
    /** Zone that defines "today" for every review calculation. */
    val timeZone: ZoneId = ZoneId.of("UTC"),
    /** Seeds demo records relative to today when the ledger is empty. */
    val demoSeed: Boolean = false,
    @field:Valid val review: Review = Review(),
    @field:Valid val security: Security = Security(),
) {
    data class Review(
        @field:Min(0) @field:Max(90) val dueSoonDays: Int = 14,
        @field:Min(1) @field:Max(3650) val maxHorizonDays: Int = 365,
    )

    data class Security(
        /** Trusted header set by an authenticating proxy (e.g. X-Auth-Request-Email); blank disables it. */
        @field:Pattern(regexp = "^$|^[A-Za-z0-9-]{1,64}$") val actorHeader: String = "",
        @field:Min(1024) @field:Max(1_048_576) val maxPayloadBytes: Int = 16_384,
        /** Sends Strict-Transport-Security; enable only when served over HTTPS. */
        val hsts: Boolean = false,
        @field:Valid val rateLimit: RateLimit = RateLimit(),
    )

    data class RateLimit(
        val enabled: Boolean = true,
        /** Write requests allowed per client within one [period]. */
        @field:Min(1) val capacity: Int = 30,
        val period: Duration = Duration.ofMinutes(1),
    )
}
