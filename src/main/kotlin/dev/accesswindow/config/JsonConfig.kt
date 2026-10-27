package dev.accesswindow.config

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.deser.std.StringDeserializer
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class JsonConfig {
    /** Trims every incoming JSON string so validation sees what will be stored. */
    @Bean
    fun trimStrings() =
        Jackson2ObjectMapperBuilderCustomizer { builder ->
            builder.deserializerByType(String::class.java, TrimmingStringDeserializer())
        }

    private class TrimmingStringDeserializer : StringDeserializer() {
        override fun deserialize(
            parser: JsonParser,
            context: DeserializationContext,
        ): String? = super.deserialize(parser, context)?.trim()
    }
}
