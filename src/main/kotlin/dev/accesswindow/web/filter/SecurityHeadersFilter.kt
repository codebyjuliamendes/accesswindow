package dev.accesswindow.web.filter

import dev.accesswindow.config.AccessWindowProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/** Strict browser hardening; the UI ships no inline script or style, so the CSP needs no exceptions. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class SecurityHeadersFilter(
    private val properties: AccessWindowProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        response.setHeader("Content-Security-Policy", CSP)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()")
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin")
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin")
        if (properties.security.hsts) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
        if (request.requestURI.startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store")
        }
        chain.doFilter(request, response)
    }

    companion object {
        val CSP =
            listOf(
                "default-src 'none'",
                "script-src 'self'",
                "style-src 'self'",
                "img-src 'self'",
                "font-src 'self'",
                "connect-src 'self'",
                "manifest-src 'self'",
                "form-action 'self'",
                "frame-ancestors 'none'",
                "base-uri 'none'",
                "object-src 'none'",
                "require-trusted-types-for 'script'",
                "trusted-types 'none'",
            ).joinToString("; ")
    }
}
