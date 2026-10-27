package dev.accesswindow.web

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.accesswindow.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.contains
import org.hamcrest.Matchers.everyItem
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.lessThan
import org.hamcrest.Matchers.lessThanOrEqualTo
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Duration

class AccessApiTest : IntegrationTest() {
    @Autowired private lateinit var json: ObjectMapper

    private fun create(body: Map<String, Any?>): ResultActionsDsl =
        mockMvc.post("/api/access") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }

    private fun decide(
        id: Long,
        body: Map<String, Any?>,
    ): ResultActionsDsl =
        mockMvc.patch("/api/access/$id") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }

    private fun ResultActionsDsl.body(): JsonNode = json.readTree(andReturn().response.contentAsString)

    private val valid =
        mapOf(
            "email" to "  Ana@Example.com ",
            "resource" to "Billing console",
            "role" to "Admin",
            "owner" to "Morgan Lee",
            "reason" to "Quarter close",
            "expiresAt" to "2024-04-30",
        )

    private fun createdId(): Long =
        create(valid)
            .andExpect { status { isCreated() } }
            .body()["id"]
            .asLong()

    @Nested
    inner class ReviewQueue {
        // Port of test/review-due.test.js from the Node version.
        @Test
        fun `review endpoint includes active overdue and due-soon access and CSV quotes values`() {
            seedDemoData()

            mockMvc.get("/api/review-due").andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(3) }
                jsonPath("$[*].daysLeft") { value(hasItem(lessThan(0))) }
                jsonPath("$[*].daysLeft") { value(everyItem(lessThanOrEqualTo(14))) }
                jsonPath("$[0].email") { value("sam@studio.co") }
                jsonPath("$[0].expiresAt") { value("2024-03-13") }
                jsonPath("$[0].reviewStatus") { value("overdue") }
            }

            val csv =
                mockMvc
                    .get("/api/review-due.csv")
                    .andExpect {
                        status { isOk() }
                        header { string("Content-Type", "text/csv;charset=UTF-8") }
                        header { string("Content-Disposition", "attachment; filename=\"access-review-due.csv\"") }
                    }.andReturn()
                    .response.contentAsString
            val lines = csv.split("\r\n")
            assertThat(lines.first()).isEqualTo("email,resource,role,owner,reason,expires_at,days_left")
            assertThat(lines).hasSize(4)
            assertThat(lines[1]).isEqualTo(
                "\"sam@studio.co\",\"Design system\",\"Editor\",\"Taylor Kim\",\"Contract design engagement\",\"2024-03-13\",\"-2\"",
            )
        }

        @Test
        fun `summary counts active access only`() {
            seedDemoData()
            val firstId = mockMvc.get("/api/access").body()[0]["id"].asLong()
            decide(firstId, mapOf("action" to "revoke")).andExpect { status { isOk() } }

            mockMvc.get("/api/summary").andExpect {
                status { isOk() }
                jsonPath("$.total") { value(4) }
                jsonPath("$.expiring") { value(2) }
                jsonPath("$.overdue") { value(0) }
                jsonPath("$.resources") { value(4) }
            }
        }

        @Test
        fun `register keeps the snake_case contract and review order`() {
            seedDemoData()

            mockMvc.get("/api/access").andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(5) }
                jsonPath("$[*].days_left") { value(contains(-2, 6, 14, 34, 67)) }
                jsonPath("$[0].expires_at") { value("2024-03-13") }
                jsonPath("$[0].status") { value("active") }
                jsonPath("$[0].review_status") { value("overdue") }
                jsonPath("$[3].review_status") { value("on_track") }
                jsonPath("$[0].created_at") { value("2024-03-15T12:00:00Z") }
            }
        }

        @Test
        fun `status moves with the clock`() {
            val id = createdId()
            clock.advance(Duration.ofDays(50))

            mockMvc.get("/api/access/$id").andExpect {
                jsonPath("$.days_left") { value(-4) }
                jsonPath("$.review_status") { value("overdue") }
            }
        }

        @Test
        fun `policy exposes the review window`() {
            mockMvc.get("/api/policy").andExpect {
                jsonPath("$.today") { value("2024-03-15") }
                jsonPath("$.timeZone") { value("UTC") }
                jsonPath("$.dueSoonDays") { value(14) }
                jsonPath("$.maxReviewDate") { value("2025-03-15") }
            }
        }
    }

    @Nested
    inner class Creating {
        @Test
        fun `creates a normalised record with a location and a creation event`() {
            val response =
                create(valid + ("actor" to "Jordan Park")).andExpect {
                    status { isCreated() }
                    jsonPath("$.email") { value("ana@example.com") }
                    jsonPath("$.status") { value("active") }
                    jsonPath("$.days_left") { value(46) }
                    jsonPath("$.review_status") { value("on_track") }
                }
            val id = response.body()["id"].asLong()
            assertThat(response.andReturn().response.getHeader("Location")).isEqualTo("/api/access/$id")

            mockMvc.get("/api/access/$id/events").andExpect {
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].type") { value("created") }
                jsonPath("$[0].actor") { value("Jordan Park") }
                jsonPath("$[0].newExpiresAt") { value("2024-04-30") }
            }
        }

        @Test
        fun `rejects invalid fields with a problem document`() {
            create(valid + mapOf("email" to "not-an-email", "owner" to " ")).andExpect {
                status { isBadRequest() }
                content { contentType(MediaType.APPLICATION_PROBLEM_JSON) }
                jsonPath("$.type") { value("/problems/validation") }
                jsonPath("$.status") { value(400) }
                jsonPath("$.instance") { value("/api/access") }
                jsonPath("$.errors[0].field") { value("email") }
                jsonPath("$.errors[1].field") { value("owner") }
                jsonPath("$.error") { value("Enter a valid email address. Owner is required.") }
            }
        }

        @Test
        fun `rejects missing fields, control characters and oversize text`() {
            create(valid - "reason").andExpect { jsonPath("$.errors[0].field") { value("reason") } }
            create(valid + ("resource" to "Line\nbreak")).andExpect { jsonPath("$.errors[0].field") { value("resource") } }
            create(valid + ("role" to "x".repeat(61))).andExpect { jsonPath("$.errors[0].field") { value("role") } }
            create(valid + ("email" to "ana@localhost")).andExpect { status { isBadRequest() } }
        }

        @Test
        fun `rejects impossible, past and far-future review dates`() {
            create(valid + ("expiresAt" to "2024-02-30")).andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].field") { value("expiresAt") }
            }
            create(valid + ("expiresAt" to "15/04/2024")).andExpect { status { isBadRequest() } }
            create(valid + ("expiresAt" to "2024-03-14")).andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Review date must be on or after 2024-03-15.") }
            }
            create(valid + ("expiresAt" to "2025-03-16")).andExpect { status { isBadRequest() } }
            create(valid + ("expiresAt" to "2024-03-15")).andExpect { status { isCreated() } }
        }

        @Test
        fun `rejects malformed bodies and wrong media types`() {
            mockMvc
                .post("/api/access") {
                    contentType = MediaType.APPLICATION_JSON
                    content = "{"
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.error") { value("Request body is missing or is not valid JSON.") }
                }
            mockMvc
                .post("/api/access") {
                    contentType = MediaType.TEXT_PLAIN
                    content = "email=a"
                }.andExpect {
                    status { isUnsupportedMediaType() }
                    jsonPath("$.error") { exists() }
                }
        }
    }

    @Nested
    inner class Deciding {
        @Test
        fun `revoke then renew keeps a full trail`() {
            val id = createdId()

            decide(id, mapOf("action" to "revoke", "actor" to "Morgan Lee", "note" to "Engagement ended")).andExpect {
                status { isOk() }
                jsonPath("$.ok") { value(true) }
                jsonPath("$.access.status") { value("revoked") }
                jsonPath("$.access.review_status") { value("revoked") }
            }
            decide(id, mapOf("action" to "revoke")).andExpect {
                status { isConflict() }
                jsonPath("$.type") { value("/problems/already-revoked") }
            }
            val renewal = mapOf("action" to "renew", "expiresAt" to "2024-06-30", "actor" to "Taylor Kim", "note" to "Re-approved")
            decide(id, renewal).andExpect {
                status { isOk() }
                jsonPath("$.access.status") { value("active") }
                jsonPath("$.access.expires_at") { value("2024-06-30") }
            }

            mockMvc.get("/api/access/$id/events").andExpect {
                jsonPath("$[*].type") { value(contains("created", "revoked", "renewed")) }
                jsonPath("$[1].actor") { value("Morgan Lee") }
                jsonPath("$[1].note") { value("Engagement ended") }
                jsonPath("$[1].previousExpiresAt") { value("2024-04-30") }
                jsonPath("$[2].actor") { value("Taylor Kim") }
                jsonPath("$[2].previousExpiresAt") { value("2024-04-30") }
                jsonPath("$[2].newExpiresAt") { value("2024-06-30") }
                jsonPath("$[2].occurredAt") { value("2024-03-15T12:00:00Z") }
            }
        }

        @Test
        fun `renew needs a future date within the horizon`() {
            val id = createdId()

            decide(id, mapOf("action" to "renew")).andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("Use revoke or renew with an expiry date.") }
            }
            decide(id, mapOf("action" to "renew", "expiresAt" to "2024-03-15")).andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Review date must be on or after 2024-03-16.") }
            }
        }

        @Test
        fun `unknown actions, records and ids are reported as problems`() {
            val id = createdId()

            decide(id, mapOf("action" to "delete")).andExpect {
                status { isBadRequest() }
                jsonPath("$.error") { value("Use revoke or renew with an expiry date.") }
            }
            decide(id + 1000, mapOf("action" to "revoke")).andExpect {
                status { isNotFound() }
                jsonPath("$.error") { value("Access record not found.") }
            }
            mockMvc.get("/api/access/abc").andExpect { status { isBadRequest() } }
            mockMvc.get("/api/access/${id + 1000}/events").andExpect { status { isNotFound() } }
            mockMvc
                .patch("/api/access/$id") { contentType = MediaType.APPLICATION_JSON }
                .andExpect { status { isBadRequest() } }
        }
    }
}
