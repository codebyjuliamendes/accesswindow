package dev.accesswindow.web

import dev.accesswindow.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

class PlatformTest : IntegrationTest() {
    @Test
    fun `health endpoints report UP`() {
        mockMvc.get("/api/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
        mockMvc.get("/actuator/health/readiness").andExpect { status { isOk() } }
    }

    @Test
    fun `serves the UI with strict security headers`() {
        mockMvc.get("/index.html").andExpect {
            status { isOk() }
            content { string(containsString("<title>AccessWindow")) }
            header { string("Content-Security-Policy", containsString("script-src 'self'")) }
            header { string("Content-Security-Policy", not(containsString("unsafe-inline"))) }
            header { string("Content-Security-Policy", containsString("frame-ancestors 'none'")) }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("X-Frame-Options", "DENY") }
            header { string("Referrer-Policy", "no-referrer") }
            header { doesNotExist("Strict-Transport-Security") }
        }
        mockMvc.get("/api/summary").andExpect { header { string("Cache-Control", "no-store") } }
    }

    @Test
    fun `propagates safe correlation ids and replaces unsafe ones`() {
        mockMvc.get("/api/summary") { header("X-Request-Id", "req-12345678") }.andExpect {
            header { string("X-Request-Id", "req-12345678") }
        }
        val generated =
            mockMvc
                .get("/api/summary") { header("X-Request-Id", "bad id\r\nx") }
                .andReturn()
                .response
                .getHeader("X-Request-Id")
        assertThat(generated).matches("[0-9a-f-]{36}")
    }

    @Test
    fun `rejects oversized payloads`() {
        mockMvc
            .post("/api/access") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"reason":"${"x".repeat(20_000)}"}"""
            }.andExpect {
                status { isPayloadTooLarge() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.type") { value("/problems/payload-too-large") }
            }
    }

    @Test
    fun `unknown api paths are problems`() {
        mockMvc.get("/api/nope").andExpect {
            status { isNotFound() }
            content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
        }
    }
}

@TestPropertySource(properties = ["accesswindow.security.rate-limit.capacity=3"])
class RateLimitTest : IntegrationTest() {
    @Test
    fun `throttles bursts of writes but not reads`() {
        repeat(3) {
            mockMvc
                .post("/api/access") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{}"
                }.andExpect { status { isBadRequest() } }
        }

        mockMvc
            .post("/api/access") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isTooManyRequests() }
                header { exists("Retry-After") }
                jsonPath("$.type") { value("/problems/rate-limited") }
            }
        mockMvc.get("/api/summary").andExpect { status { isOk() } }
    }
}

@TestPropertySource(properties = ["accesswindow.security.actor-header=X-Auth-Request-Email", "accesswindow.security.hsts=true"])
class TrustedProxyTest : IntegrationTest() {
    @Test
    fun `proxy identity wins over the self-declared actor`() {
        val body =
            """
            {"email":"a@b.io","resource":"VPN","role":"Member","owner":"Ops",
             "reason":"Onboarding","expiresAt":"2024-04-01","actor":"someone"}
            """
        val id =
            mockMvc
                .post("/api/access") {
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                    header("X-Auth-Request-Email", "julia@company.io")
                }.andExpect {
                    status { isCreated() }
                    header { string("Strict-Transport-Security", containsString("max-age=")) }
                }.andReturn()
                .response.contentAsString
                .substringAfter("\"id\":")
                .substringBefore(",")

        mockMvc.get("/api/access/$id/events").andExpect {
            jsonPath("$[0].actor") { value("julia@company.io") }
        }
    }
}
