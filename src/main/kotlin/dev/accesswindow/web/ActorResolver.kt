package dev.accesswindow.web

import dev.accesswindow.config.AccessWindowProperties
import dev.accesswindow.domain.Actor
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

/**
 * Picks who made a change. A configured proxy header wins over the self-declared name in
 * the body, so deployments behind SSO get an attributable audit trail.
 */
@Component
class ActorResolver(
    properties: AccessWindowProperties,
) {
    private val header = properties.security.actorHeader

    fun resolve(
        request: HttpServletRequest,
        claimed: String?,
    ): Actor {
        val proxied = header.takeIf { it.isNotBlank() }?.let { request.getHeader(it) }
        // A present proxy header is authoritative: never fall back to the client's claim when it is malformed.
        val name = if (proxied != null) clean(proxied) else claimed?.let(::clean)
        return name?.let(::Actor) ?: Actor.UNSPECIFIED
    }

    private fun clean(value: String): String? =
        value.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH && it.none(Char::isISOControl) }

    companion object {
        private const val MAX_LENGTH = 120
    }
}
