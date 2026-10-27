package dev.accesswindow.support

import dev.accesswindow.application.AccessReviewService
import dev.accesswindow.config.DemoDataSeeder
import dev.accesswindow.domain.AccessLedger
import dev.accesswindow.domain.ReviewCalendar
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import java.time.Instant
import java.time.ZoneId

/** Full application over MockMvc with a fixed clock (2024-03-15, UTC) and an emptied ledger per test. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(IntegrationTest.ClockConfig::class)
abstract class IntegrationTest {
    @Autowired protected lateinit var mockMvc: MockMvc

    @Autowired protected lateinit var clock: MutableClock

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var ledger: AccessLedger

    @Autowired private lateinit var service: AccessReviewService

    @Autowired private lateinit var calendar: ReviewCalendar

    @BeforeEach
    fun resetLedger() {
        clock.set(NOW)
        jdbc.update("DELETE FROM access_event")
        jdbc.update("DELETE FROM access_grant")
    }

    protected fun seedDemoData() = DemoDataSeeder(ledger, service, calendar).run(DefaultApplicationArguments())

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfig {
        @Bean
        @Primary
        fun testClock() = MutableClock(NOW, ZoneId.of("UTC"))
    }

    companion object {
        val NOW: Instant = Instant.parse("2024-03-15T12:00:00Z")
    }
}
