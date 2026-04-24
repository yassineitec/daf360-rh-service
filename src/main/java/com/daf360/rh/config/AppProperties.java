package com.daf360.rh.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    private String jwtSecret;
    private long jwtExpirySeconds = 3600;
    private List<String> allowedOrigins = List.of();
    private String storagePath = "./uploads/hr";
}
