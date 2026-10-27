package dev.accesswindow.web

import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

data class FieldProblem(
    val field: String,
    val message: String,
)

/** Raised for semantic field errors found after bean validation (e.g. 2025-02-30). */
class InvalidFieldException(
    val field: String,
    override val message: String,
) : RuntimeException(message)

fun validationProblem(errors: List<FieldProblem>): ProblemDetail {
    val detail = errors.map { it.message }.distinct().joinToString(" ")
    return Problems.of(HttpStatus.BAD_REQUEST, "validation", "Invalid request", detail).apply {
        setProperty("errors", errors)
    }
}

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handleMethodArgumentNotValid(
        ex: MethodArgumentNotValidException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val errors =
            ex.bindingResult.fieldErrors
                .sortedBy { it.field }
                .distinctBy { it.field }
                .map { FieldProblem(it.field, it.defaultMessage ?: "Invalid value.") }
        return handleExceptionInternal(ex, validationProblem(errors), headers, HttpStatus.BAD_REQUEST, request)
    }

    override fun handleHttpMessageNotReadable(
        ex: HttpMessageNotReadableException,
        headers: HttpHeaders,
        status: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        val problem =
            Problems.of(
                HttpStatus.BAD_REQUEST,
                "malformed-body",
                "Malformed request",
                "Request body is missing or is not valid JSON.",
            )
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request)
    }

    @ExceptionHandler(InvalidFieldException::class)
    fun invalidField(ex: InvalidFieldException) = respond(validationProblem(listOf(FieldProblem(ex.field, ex.message))))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(ex: MethodArgumentTypeMismatchException) =
        respond(Problems.of(HttpStatus.BAD_REQUEST, "invalid-parameter", "Invalid parameter", "Invalid value for '${ex.name}'."))

    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun concurrentUpdate(ex: OptimisticLockingFailureException): ResponseEntity<Any> {
        log.warn("access.conflict {}", ex.message)
        return respond(
            Problems.of(
                HttpStatus.CONFLICT,
                "concurrent-update",
                "Concurrent update",
                "This record changed in the meantime. Reload and try again.",
            ),
        )
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(ex: Exception): ResponseEntity<Any> {
        log.error("request.failed", ex)
        return respond(
            Problems.of(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Internal error", "Something went wrong. Try again later."),
        )
    }

    /** Adds the legacy `error` member to framework-generated problems too. */
    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        if (body is ProblemDetail && body.properties?.containsKey(Problems.ERROR_PROPERTY) != true) {
            body.setProperty(Problems.ERROR_PROPERTY, body.detail ?: body.title)
        }
        return super.createResponseEntity(body, headers, statusCode, request)
    }

    private fun respond(problem: ProblemDetail): ResponseEntity<Any> =
        ResponseEntity.status(problem.status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem)
}
