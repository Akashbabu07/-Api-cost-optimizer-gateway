package com.example.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RouteConfig {
    @Value("${services.auth.url}")
    private String authServiceUrl;

    @Value("${services.metrics.url}")
    private String metricsServiceUrl;

    @Value("${services.analytics.url}")
    private String analyticsServiceUrl;

    @Value("${services.recommendations.url}")
    private String recommendationsServiceUrl;

    @Value("${services.dashboard.url}")
    private String dashboardServiceUrl;

    @Bean
    public RouteLocator customRoutes(RouteLocatorBuilder builder) {

        return builder.routes()

                .route("auth-service",
                        r -> r.path("/auth/**")
                                .uri(authServiceUrl))

                .route("metrics-service",
                        r -> r.path("/metrics/**")
                                .uri(metricsServiceUrl))

                .route("analytics-service",
                        r -> r.path("/analytics/**")
                                .filters(f -> f.circuitBreaker(config -> config
                                        .setName("analyticsService")
                                        .setFallbackUri("forward:/fallback/analytics")))
                                .uri(analyticsServiceUrl))

                .route("recommendation-service",
                        r -> r.path("/recommendations/**")
                                .filters(f -> f.circuitBreaker(config -> config
                                        .setName("recommendationService")
                                        .setFallbackUri("forward:/fallback/recommendations")))
                                .uri(recommendationsServiceUrl))

                .route("dashboard-service",
                        r -> r.path("/dashboard/**")
                                .uri(dashboardServiceUrl))

                .build();
    }
}