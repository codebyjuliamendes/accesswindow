package dev.accesswindow.web

import org.springframework.boot.actuate.health.HealthEndpoint
import org.springframework.boot.actuate.health.Status
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Simple liveness+database probe for load balancers; Actuator keeps the detailed endpoints. */
@RestController
class HealthController(
    private val health: HealthEndpoint,
) {
    @GetMapping("/api/health")
    fun health(): ResponseEntity<Map<String, String>> {
        val status = health.health().status
        val code = if (status == Status.UP) HttpStatus.OK else HttpStatus.SERVICE_UNAVAILABLE
        return ResponseEntity.status(code).body(mapOf("status" to status.code))
    }
}
