# API Gateway

> The single entry point for all client traffic in the API Cost Optimizer platform. Built on **Spring Cloud Gateway (WebFlux)** — handles JWT authentication, IP-based rate limiting, request logging, circuit breaking, and smart routing across all five backend microservices.
 > you can visit github repo of other service here
> auth =https://github.com/Akashbabu07/Api-cost-optimizer-auth.git
> metrics=https://github.com/Akashbabu07/Api-cost-optimizer-metrics.git
> dashboard=https://github.com/Akashbabu07/Api-cost-optimizer-dashboard.git
> analytics=https://github.com/Akashbabu07/Api-cost-optimizer-analytics.git
> recommendation=https://github.com/Akashbabu07/Api-cost-optimizer-recommendation.git

## Overview

Every HTTP request from the frontend passes through this gateway before reaching any service. It runs on the **reactive WebFlux stack** (non-blocking, event-loop based) — meaning it handles high concurrency without spawning threads per request.

The gateway does four things before forwarding a request:

1. **Rate limit** by IP address via Redis (checked first, order `-3`)
2. **Log** the incoming request with a unique trace ID (order `-2`)
3. **Validate JWT** — extracts `userId` and `role` from the token and injects them as request headers (order `0`)
4. **Route** to the correct downstream service, with circuit breakers on Analytics and Recommendation

---

## Port

```
8080
```

---

## Filter Execution Order

Filters run in ascending order number — lower runs first.

| Order | Filter | Responsibility |
|---|---|---|
| `-3` | `RedisRateLimitFilter` | IP-based rate limiting via Redis |
| `-2` | `LoggingFilter` | Request/response logging with trace ID |
| `0` | `JwtAuthenticationFilter` | JWT validation + header injection |
| — | `RouteConfig` | Path-based routing to downstream services |

---

## Filter Details

### 1. RedisRateLimitFilter

Limits each IP address to **10 requests per 60-second window** using Redis as the counter store.

- Uses `ReactiveRedisTemplate` — fully non-blocking
- Key format: `rate:<ip_address>`
- On first request, sets a 60s TTL on the Redis key
- Returns `429 Too Many Requests` when the limit is exceeded
- Fails open on Redis errors (lets the request through rather than blocking it)

```
Window:  60 seconds
Limit:   10 requests per IP
Key:     rate:{ip}
On fail: passes request through (fail-open)
```

### 2. LoggingFilter

Assigns every request a random UUID trace ID and logs both the inbound request and the outbound response.

```
REQ [550e8400-e29b-41d4-a716] → GET /dashboard/summary
RES [550e8400-e29b-41d4-a716] status=200 time=43ms
```

Useful for correlating frontend errors with backend logs in Render's log viewer.

### 3. JwtAuthenticationFilter

Validates the `Authorization: Bearer <token>` header on every non-public route.

**Public routes (no JWT needed):**
- `/auth/**`
- `/swagger-ui/**`
- `/v3/api-docs/**`
- `/actuator/**`
- `OPTIONS` preflight requests (CORS)

**On valid JWT:**
- Extracts `userId` and `role` claims from the token
- Injects them as `X-User-Id` and `X-Role` headers on the forwarded request
- Downstream services can trust these headers without re-validating the token

**On invalid/missing JWT:**
- Returns `401 Unauthorized` immediately — request never reaches the downstream service

```
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
                          ↓
              X-User-Id: 42
              X-Role: USER
                          ↓
              forwarded to downstream service
```

---

## Routing

Routes are defined in `RouteConfig.java` and match on URL path prefix.

| Route ID | Path Pattern | Downstream Service | Circuit Breaker | Fallback |
|---|---|---|---|---|
| `auth-service` | `/auth/**` | Auth `:8081` | No | — |
| `metrics-service` | `/metrics/**` | Metrics `:8082` | No | — |
| `analytics-service` | `/analytics/**` | Analytics `:8083` | Yes (`analyticsService`) | `/fallback/analytics` |
| `recommendation-service` | `/recommendations/**` | Recommendation `:8084` | Yes (`recommendationService`) | `/fallback/recommendations` |
| `dashboard-service` | `/dashboard/**` | Dashboard `:8085` | No | — |

### Fallback Responses

When a circuit is open, the `FallbackController` returns a human-readable message instead of an error:

- **Analytics fallback:** `"Analytics Service is temporarily unavailable. Please try again later."`
- **Recommendations fallback:** `"Recommendation Service is down. Showing cached/default suggestions."`

---

## Circuit Breaker Configuration

Powered by **Resilience4j** via `spring-cloud-starter-circuitbreaker-reactor-resilience4j`.

| Setting | analyticsService | recommendationService |
|---|---|---|
| Sliding window size | 5 requests | 5 requests |
| Failure rate threshold | 50% | 50% |
| Wait in open state | 10 seconds | 10 seconds |
| Timeout per request | 5 seconds | 5 seconds |

**How it works:** If 3 out of 5 consecutive requests to Analytics fail or time out, the circuit opens. For the next 10 seconds all Analytics requests are immediately redirected to the fallback — no waiting. After 10s, one test request goes through. If it succeeds, the circuit closes.

---

## CORS Configuration

Configured in `CorsConfig.java` via `CorsWebFilter` (WebFlux-compatible).

| Setting | Value |
|---|---|
| Allowed Origins | Configurable via `CORS_ALLOWED_ORIGINS` env var |
| Default Origins | `http://localhost:3000`, `http://localhost:5173` |
| Allowed Methods | `GET, POST, PUT, DELETE, OPTIONS, PATCH` |
| Allowed Headers | `*` (all headers) |
| Allow Credentials | `true` |

---

## JWT Verification

The gateway uses **JJWT 0.13.0** (`io.jsonwebtoken`) to verify tokens.

- Algorithm: **HMAC-SHA** (key derived from `JWT_SECRET`)
- The gateway **only verifies** — it does not issue tokens (that's the Auth service's job)
- The same `JWT_SECRET` must be set on both the Gateway and the Auth service
- Token claims extracted: `userId`, `role`

---

## Project Structure

```
gateway/
├── src/main/java/com/example/gateway/
│   ├── GatewayApplication.java          # Entry point
│   ├── config/
│   │   ├── CorsConfig.java              # CORS WebFilter setup
│   │   ├── RedisConfig.java             # ReactiveRedisTemplate bean
│   │   ├── RouteConfig.java             # All route definitions
│   │   └── SecurityConfig.java          # WebFlux security (CSRF off, permit-all)
│   ├── filter/
│   │   ├── JwtAuthenticationFilter.java # JWT validation GlobalFilter
│   │   ├── LoggingFilter.java           # Trace ID request/response logger
│   │   └── RedisRateLimitFilter.java    # IP rate limiter GlobalFilter
│   ├── security/
│   │   └── JwtUtil.java                 # JJWT token parsing utility
│   └── exception/
│       └── FallbackController.java      # Circuit breaker fallback responses
├── src/main/resources/
│   └── application.yaml
└── pom.xml
```

---

## Configuration

Full `application.yaml`:

```yaml
server:
  port: ${PORT:8080}

spring:
  application:
    name: api-gateway

  data:
    redis:
      host: ${REDIS_HOST}
      port: ${REDIS_PORT}
      password: ${REDIS_PASSWORD}

jwt:
  secret: ${JWT_SECRET}

services:
  auth.url:            ${AUTH_SERVICE_URL:http://localhost:8081}
  metrics.url:         ${METRICS_SERVICE_URL:http://localhost:8082}
  analytics.url:       ${ANALYTICS_SERVICE_URL:http://localhost:8083}
  recommendations.url: ${RECOMMENDATION_SERVICE_URL:http://localhost:8084}
  dashboard.url:       ${DASHBOARD_SERVICE_URL:http://localhost:8085}

cors:
  allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}

resilience4j:
  timelimiter:
    instances:
      analyticsService:
        timeoutDuration: 5s
      recommendationService:
        timeoutDuration: 5s
  circuitbreaker:
    instances:
      analyticsService:
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        slidingWindowSize: 5
      recommendationService:
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        slidingWindowSize: 5
```

---

## Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `JWT_SECRET` | ✅ Yes | — | HMAC signing secret — must match Auth service (min 32 chars) |
| `REDIS_HOST` | ✅ Yes | — | Redis hostname |
| `REDIS_PORT` | ✅ Yes | — | Redis port |
| `REDIS_PASSWORD` | ✅ Yes | — | Redis password |
| `PORT` | No | `8080` | Server port |
| `CORS_ALLOWED_ORIGINS` | No | `http://localhost:3000,http://localhost:5173` | Comma-separated allowed origins |
| `AUTH_SERVICE_URL` | No | `http://localhost:8081` | Auth service base URL |
| `METRICS_SERVICE_URL` | No | `http://localhost:8082` | Metrics service base URL |
| `ANALYTICS_SERVICE_URL` | No | `http://localhost:8083` | Analytics service base URL |
| `RECOMMENDATION_SERVICE_URL` | No | `http://localhost:8084` | Recommendation service base URL |
| `DASHBOARD_SERVICE_URL` | No | `http://localhost:8085` | Dashboard service base URL |

---

## Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `spring-boot-starter-webflux` | 3.5.15 | Reactive web server |
| `spring-cloud-starter-gateway-server-webflux` | 2025.0.2 | Gateway routing engine |
| `spring-boot-starter-data-redis-reactive` | 3.5.15 | Non-blocking Redis client |
| `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | 2025.0.2 | Reactive circuit breakers |
| `spring-boot-starter-security` | 3.5.15 | WebFlux security config |
| `jjwt-api / jjwt-impl / jjwt-jackson` | 0.13.0 | JWT parsing |
| `lombok` | latest | Boilerplate reduction |
| `spring-boot-starter-actuator` | 3.5.15 | Health + info endpoints |

> **Note:** Uses `spring-cloud-starter-gateway-server-webflux` — the updated non-deprecated artifact replacing the old `spring-cloud-starter-gateway`.

---

## Health Check

```
GET http://localhost:8080/actuator/health
```

```json
{
  "status": "UP"
}
```

---

## Running Locally

```bash
cd gateway
./mvnw spring-boot:run
```

Make sure Redis is running and all environment variables are set before starting.

---

## Related Services

| Service | Repo / Path | Port |
|---|---|---|
| Auth | `services/auth` | 8081 |
| Metrics | `services/metrics` | 8082 |
| Analytics | `services/analytics` | 8083 |
| Recommendation | `services/recommendation` | 8084 |
| Dashboard | `services/dashboard` | 8085 |
