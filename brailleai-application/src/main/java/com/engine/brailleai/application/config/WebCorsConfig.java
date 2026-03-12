package com.engine.brailleai.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

/**
 * CORS configuration for local and tunneled UI access.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:" +
            "http://localhost:5173," +
            "http://127.0.0.1:5173," +
            "http://localhost:5500," +
            "http://127.0.0.1:5500," +
            "https://*.ngrok-free.dev," +
            "https://*.ngrok-free.app," +
            "https://*.ngrok.dev," +
            "https://*.ngrok.app}")
    private String allowedOrigins;

    private String[] resolveAllowedOriginPatterns() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(resolveAllowedOriginPatterns())
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition")
                .maxAge(3600);
    }
}
