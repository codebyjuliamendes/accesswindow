package dev.accesswindow.web

import dev.accesswindow.application.GrantView
import dev.accesswindow.domain.AccessGrant
import dev.accesswindow.domain.GrantState
import dev.accesswindow.domain.ReviewStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate

class ReviewCsvTest {
    private fun view(
        reason: String,
        daysLeft: Long = -2,
    ) = GrantView(
        AccessGrant(
            id = 1,
            email = "sam@studio.co",
            resource = "Design, \"core\" system",
            role = "Editor",
            owner = "Taylor Kim",
            reason = reason,
            reviewDate = LocalDate.of(2024, 3, 13),
            state = GrantState.ACTIVE,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
        ),
        daysLeft,
        ReviewStatus.OVERDUE,
    )

    @Test
    fun `quotes every cell and separates rows with CRLF`() {
        val csv = ReviewCsv.render(listOf(view("Contract"), view("Audit", 3)))

        assertThat(csv.split("\r\n")).containsExactly(
            "email,resource,role,owner,reason,expires_at,days_left",
            "\"sam@studio.co\",\"Design, \"\"core\"\" system\",\"Editor\",\"Taylor Kim\",\"Contract\",\"2024-03-13\",\"-2\"",
            "\"sam@studio.co\",\"Design, \"\"core\"\" system\",\"Editor\",\"Taylor Kim\",\"Audit\",\"2024-03-13\",\"3\"",
        )
    }

    @Test
    fun `neutralises spreadsheet formulas in text cells`() {
        assertThat(ReviewCsv.text("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"")
        assertThat(ReviewCsv.text("+1")).isEqualTo("\"'+1\"")
        assertThat(ReviewCsv.text("@SUM")).isEqualTo("\"'@SUM\"")
        assertThat(ReviewCsv.text("plain")).isEqualTo("\"plain\"")
    }

    @Test
    fun `empty queue renders the header only`() {
        assertThat(ReviewCsv.render(emptyList())).isEqualTo(ReviewCsv.HEADER)
    }
}
