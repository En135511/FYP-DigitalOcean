package com.engine.brailleai.application.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebCorsConfigTest {

    @Test
    void appliesExpectedCorsRulesForApiRoutes() throws Exception {
        WebCorsConfig config = new WebCorsConfig();
        CorsRegistry registry = new CorsRegistry();

        config.addCorsMappings(registry);

        Method method = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
        method.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, CorsConfiguration> mappings =
                (Map<String, CorsConfiguration>) method.invoke(registry);

        CorsConfiguration apiConfig = mappings.get("/api/**");
        assertNotNull(apiConfig);
        assertTrue(apiConfig.getAllowedOrigins().contains("http://localhost:5173"));
        assertTrue(apiConfig.getAllowedOrigins().contains("http://127.0.0.1:5500"));
        assertEquals(List.of("GET", "POST", "OPTIONS"), apiConfig.getAllowedMethods());
        assertEquals(List.of("*"), apiConfig.getAllowedHeaders());
    }
}
