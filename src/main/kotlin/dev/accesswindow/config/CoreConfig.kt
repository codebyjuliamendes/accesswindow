package dev.accesswindow.config

import dev.accesswindow.domain.ReviewCalendar
import dev.accesswindow.domain.ReviewPolicy
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class CoreConfig {
    @Bean
    @ConditionalOnMissingBean
    fun clock(properties: AccessWindowProperties): Clock = Clock.system(properties.timeZone)

    @Bean
    fun reviewPolicy(properties: AccessWindowProperties) = ReviewPolicy(properties.review.dueSoonDays, properties.review.maxHorizonDays)

    @Bean
    fun reviewCalendar(clock: Clock) = ReviewCalendar(clock)
}
