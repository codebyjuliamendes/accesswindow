package dev.accesswindow

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class AccessWindowApplication

@Suppress("SpreadOperator") // idiomatic Spring Boot entry point
fun main(args: Array<String>) {
    runApplication<AccessWindowApplication>(*args)
}
