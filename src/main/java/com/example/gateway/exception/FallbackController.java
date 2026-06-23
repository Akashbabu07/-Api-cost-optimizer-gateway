package com.example.gateway.exception;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/analytics")
    public String analyticsFallback() {
        return "Analytics Service is temporarily unavailable. Please try again later.";
    }

    @GetMapping("/recommendations")
    public String recommendationFallback() {
        return "Recommendation Service is down. Showing cached/default suggestions.";
    }
}