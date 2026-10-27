package dev.accesswindow.web.filter

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Propagates X-Request-Id into the MDC (and so into every JSON log line) and logs API requests. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val id = request.getHeader(HEADER)?.takeIf { SAFE_ID.matches(it) } ?: UUID.randomUUID().toString()
        val started = System.nanoTime()
        MDC.put(MDC_KEY, id)
        response.setHeader(HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            if (request.requestURI.startsWith("/api/")) {
                val millis = (System.nanoTime() - started) / NANOS_PER_MILLI
                log.info("http {} {} status={} durationMs={}", request.method, request.requestURI, response.status, millis)
            }
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "correlationId"
        private const val NANOS_PER_MILLI = 1_000_000
        private val SAFE_ID = Regex("^[A-Za-z0-9._-]{8,64}$")
    }
}
