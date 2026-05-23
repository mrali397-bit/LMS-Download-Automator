package com.downloadc.downloadc.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    // Locally: http://localhost:5500
    // On Koyeb: set APP_ALLOWED_ORIGIN = https://your-app.koyeb.app
    @Value("${app.allowed-origin:http://localhost:5500}")
    private String allowedOrigin;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigin)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowCredentials(true);
    }
}