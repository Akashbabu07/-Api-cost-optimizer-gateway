# Architecture

Components:
- API Gateway
- Auth Service
- Metrics Service
- Analytics Service
- Dashboard Service
- Recommendation Service

Communication:
Gateway → Services
Metrics → Analytics (Kafka)
Dashboard → Analytics (Feign)
Recommendation → Analytics (Feign)