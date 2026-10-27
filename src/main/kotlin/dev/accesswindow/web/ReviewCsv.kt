package dev.accesswindow.web

import dev.accesswindow.application.GrantView

/** RFC 4180 CSV with spreadsheet formula injection neutralised. */
object ReviewCsv {
    const val HEADER = "email,resource,role,owner,reason,expires_at,days_left"
    private const val CRLF = "\r\n"
    private val FORMULA_PREFIXES = setOf('=', '+', '-', '@', '\t', '\r')

    fun render(rows: List<GrantView>): String =
        rows.joinToString(separator = "", prefix = HEADER) { view ->
            val grant = view.grant
            CRLF +
                listOf(grant.email, grant.resource, grant.role, grant.owner, grant.reason)
                    .map(::text)
                    .plus(quote(grant.reviewDate.toString()))
                    .plus(quote(view.daysLeft.toString()))
                    .joinToString(",")
        }

    fun text(value: String): String = quote(if (value.firstOrNull() in FORMULA_PREFIXES) "'$value" else value)

    private fun quote(value: String) = "\"" + value.replace("\"", "\"\"") + "\""
}
