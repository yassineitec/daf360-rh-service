package com.daf360.rh.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Explicit ObjectMapper bean.
 *
 * Jackson2ObjectMapperBuilderCustomizer was removed in Spring Boot 4.0 / Jackson 3.
 * We configure the mapper directly here to:
 *   - Register JavaTimeModule → OffsetDateTime / LocalDate as ISO-8601 strings
 *   - Disable WRITE_DATES_AS_TIMESTAMPS (Jackson 2 compat flag still present)
 *   - Disable FAIL_ON_UNKNOWN_PROPERTIES for tolerant API deserialization
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
    }
}
