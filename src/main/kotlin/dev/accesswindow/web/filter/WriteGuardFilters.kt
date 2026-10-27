package dev.accesswindow.web.filter

import dev.accesswindow.config.AccessWindowProperties
import dev.accesswindow.web.ProblemWriter
import dev.accesswindow.web.Problems
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader

private val WRITE_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

private fun HttpServletRequest.isApiWrite() = method in WRITE_METHODS && requestURI.startsWith("/api/")

/** Limits API writes per client address (resolved behind proxies via server.forward-headers-strategy). */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class WriteRateLimitFilter(
    properties: AccessWindowProperties,
    private val problems: ProblemWriter,
) : OncePerRequestFilter() {
    private val settings = properties.security.rateLimit
    private val limiter = WriteRateLimiter(settings.capacity, settings.period)

    override fun shouldNotFilter(request: HttpServletRequest) = !settings.enabled || !request.isApiWrite()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val wait = limiter.tryAcquire(request.remoteAddr)
        if (wait == 0L) {
            chain.doFilter(request, response)
            return
        }
        response.setHeader("Retry-After", wait.toString())
        problems.write(
            request,
            response,
            Problems.of(
                HttpStatus.TOO_MANY_REQUESTS,
                "rate-limited",
                "Too many requests",
                "Too many changes in a short time. Try again in $wait s.",
            ),
        )
    }
}

/** Caps API request bodies, including chunked uploads without Content-Length. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 3)
class PayloadLimitFilter(
    properties: AccessWindowProperties,
    private val problems: ProblemWriter,
) : OncePerRequestFilter() {
    private val maxBytes = properties.security.maxPayloadBytes

    override fun shouldNotFilter(request: HttpServletRequest) = !request.isApiWrite()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val body =
            if (request.contentLengthLong > maxBytes) null else request.inputStream.readNBytes(maxBytes + 1).takeIf { it.size <= maxBytes }
        if (body == null) {
            response.setHeader("Connection", "close")
            problems.write(
                request,
                response,
                Problems.of(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "payload-too-large",
                    "Payload too large",
                    "Request body exceeds $maxBytes bytes.",
                ),
            )
            return
        }
        chain.doFilter(CachedBodyRequest(request, body), response)
    }

    private class CachedBodyRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getContentLength() = body.size

        override fun getContentLengthLong() = body.size.toLong()

        override fun getReader() = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: Charsets.UTF_8.name()))

        override fun getInputStream(): ServletInputStream {
            val source = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read() = source.read()

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) = source.read(b, off, len)

                override fun isFinished() = source.available() == 0

                override fun isReady() = true

                override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException("synchronous body only")
            }
        }
    }
}
