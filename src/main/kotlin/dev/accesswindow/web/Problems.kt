package dev.accesswindow.web

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.stereotype.Component
import java.net.URI

/** RFC 9457 problems. `error` mirrors `detail` for clients of the original API. */
object Problems {
    const val ERROR_PROPERTY = "error"

    fun of(
        status: HttpStatus,
        slug: String,
        title: String,
        detail: String,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            type = URI.create("/problems/$slug")
            this.title = title
            setProperty(ERROR_PROPERTY, detail)
        }
}

/** Writes problems from servlet filters, which run outside Spring MVC's exception handling. */
@Component
class ProblemWriter(
    private val objectMapper: ObjectMapper,
) {
    fun write(
        request: HttpServletRequest,
        response: HttpServletResponse,
        problem: ProblemDetail,
    ) {
        problem.instance = URI.create(request.requestURI)
        response.status = problem.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(response.outputStream, problem)
    }
}
