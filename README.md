# Shortel — Production-Grade URL Shortener Platform

A distributed URL shortener built with a **microservices architecture** on Java 21 + Spring Boot 3. Designed for high-throughput redirect performance, real-time analytics, and multi-tenant quota management.

---

## Table of Contents

- [What It Does](#what-it-does)
- [Tech Stack](#tech-stack)
- [System Architecture](#system-architecture)
- [Services](#services)
- [Data Flow Walkthroughs](#data-flow-walkthroughs)
- [Database Schema](#database-schema)
- [API Reference](#api-reference)
- [Key Design Decisions](#key-design-decisions)
- [Running Locally](#running-locally)
- [Project Structure](#project-structure)

---

## What It Does

Shortel shortens long URLs into compact 7-character alphanumeric codes and redirects visitors at high throughput while recording analytics.

**Core capabilities:**
- **URL shortening** — custom aliases or auto-generated codes via Snowflake IDs + Base62 encoding
- **High-speed redirects** — cache-first (Redis → MySQL); analytics never blocks the redirect path
- **Real-time analytics** — click counts and unique visitor estimates (HyperLogLog) with hourly MySQL persistence
- **Multi-tenancy** — per-tenant URL and click quotas with monthly billing windows
- **JWT authentication** — access tokens (15 min) + refresh tokens (7 days, Redis-backed)
- **Fault tolerance** — circuit breakers on all gateway routes, fail-open quota checks

---

## Tech Stack

| Layer | Technology | Version |
|---|---|---|
| Language | Java | 21 (Eclipse Temurin) |
| Framework | Spring Boot | 3.2.4 |
| API Gateway | Spring Cloud Gateway | 2023.0.1 |
| Database | MySQL | 8.0 |
| Cache / Counters | Redis | 7.2 |
| Message Broker | Apache Kafka (Confluent) | 7.6.0 |
| ORM | Spring Data JPA / Hibernate | — |
| JWT | JJWT | 0.12.5 |
| Circuit Breaker | Resilience4j | — |
| Build | Maven | 3.9.6 |
| Containers | Docker + Docker Compose | — |

---

## System Architecture

```
                         ┌─────────────────────────────────┐
                         │        API Gateway :8080         │
Internet ───────────────▶│  • JWT validation (global filter)│
                         │  • Circuit breakers (Resilience4j│
                         │  • CORS + route predicates       │
                         └──┬──────┬──────┬──────┬─────────┘
                            │      │      │      │
               ┌────────────▼─┐  ┌─▼────┐ │  ┌──▼──────────┐
               │ auth-service │  │ url- │ │  │  analytics-  │
               │    :8081     │  │service│ │  │  service     │
               │              │  │:8082  │ │  │  :8084       │
               │ • JWT issue  │  │       │ │  │              │
               │ • refresh    │  │• CRUD │ │  │• Kafka cons. │
               │ • revoke     │  │• quota│ │  │• Redis INCR  │
               └──────┬───────┘  │• cache│ │  │• MySQL flush │
                      │          │  prime│ │  └──────┬───────┘
                      │          └───┬───┘ │         │
                      │              │     │         │
               ┌──────▼──────────────▼─────▼──┐      │
               │              Redis            │◀─────┘
               │  • url:{code}   (URL cache)  │
               │  • clicks:{id}  (counters)   │
               │  • hll:{id}     (HyperLogLog)│
               │  • quota:{t}:*  (quotas)     │
               │  • refresh:{t}  (tokens)     │
               └──────────────────────────────┘

               ┌─────────────────────────────────────────┐
               │              redirect-service :8083      │
               │  HOT PATH: cache-first, zero sync writes │
               │  Redis → MySQL → HTTP 301/302            │
               │  Async: publish click event to Kafka     │
               └───────────┬────────────────┬─────────────┘
                           │                │
                    ┌──────▼──────┐  ┌──────▼──────┐
                    │    MySQL    │  │    Kafka     │
                    │  • tenants  │  │ shortel.click│
                    │  • urls     │  │ shortel.url- │
                    │  • analytics│  │   events     │
                    │    _hourly  │  └─────────────┘
                    └─────────────┘

               ┌────────────────────┐
               │ tenant-service:8085│  ┌───────────────────┐
               │ • quota tracking   │  │ id-generator:8086 │
               │ • plan management  │  │ • Snowflake IDs   │
               └────────────────────┘  │ • 4096 IDs/ms     │
                                       └───────────────────┘
```

### Service-to-Service Communication

```
url-service ──REST──▶ id-generator-service   (GET /api/id/next)
url-service ──REST──▶ tenant-service          (quota check + increment)
url-service ──Kafka─▶ shortel.url-events      (acks=1, at-least-once)
redirect-service ─Kafka─▶ shortel.clicks      (acks=0, fire-and-forget)
analytics-service ◀─Kafka── shortel.clicks    (consumer group: analytics-group)
```

---

## Services

### 1. `api-gateway` — Port 8080

Single entry point for all client traffic. Built on **Spring Cloud Gateway** (reactive/Netty).

**Responsibilities:**
- **JWT validation** via a global `GatewayFilter` (order -100). Validates HMAC-SHA256 signature, injects `X-User-Id`, `X-User-Role`, `X-User-Email` headers for downstream services.
- **Routing** — path-based predicates route to the correct service
- **Circuit breaking** — Resilience4j protects each route independently
- **CORS** — centralised CORS policy

**Routes:**

| Path | Upstream | Auth | Circuit Breaker Config |
|---|---|---|---|
| `/auth/**` | auth-service:8081 | Public | 10-window, 50% threshold, 10s wait |
| `/api/v1/urls/**` | url-service:8082 | JWT | 10-window, 50% threshold, 10s wait |
| `/api/v1/analytics/**` | analytics-service:8084 | JWT | 10-window, 50% threshold, 10s wait |
| `/api/v1/tenants/**` | tenant-service:8085 | JWT | 10-window, 50% threshold, 10s wait |
| `/{code:[A-Za-z0-9]+}` | redirect-service:8083 | Public | **20-window, 60% threshold, 5s wait** |
| `/resolve/**` | redirect-service:8083 | Public | None |

The redirect circuit breaker has a larger window and higher threshold because redirect traffic is much higher volume.

---

### 2. `auth-service` — Port 8081

**Responsibilities:** JWT issuance, token refresh, token revocation.

**Endpoints:**
- `POST /auth/token` — login (Phase 1: accepts any credentials, returns JWT for userId=1)
- `POST /auth/refresh` — exchange refresh token for new access token
- `POST /auth/logout` — revoke refresh token
- `GET /auth/validate` — validate and decode an access token

**Token storage:**
- Access token — stateless JWT, 15-minute TTL, signed with HMAC-SHA256
- Refresh token — UUID stored in Redis as `refresh:{uuid}` → `userId`, 7-day TTL

---

### 3. `id-generator-service` — Port 8086

Generates globally unique 64-bit IDs using the **Snowflake algorithm**.

```
[ 41 bits: ms timestamp since 2023-11-14 epoch ]
[ 10 bits: machine ID (0-1023)                 ]
[ 12 bits: sequence counter (0-4095 per ms)    ]
```

- Capacity: **4,096 IDs/millisecond** per machine instance
- On sequence exhaustion: spin-waits for the next millisecond (non-blocking in terms of callers — the HTTP request waits but no threads are blocked in the JVM queue)
- On clock regression: throws `IllegalStateException` (safe rejection)

**Endpoints:**
- `GET /api/id/next` — single ID
- `GET /api/id/batch?count=N` — batch of IDs

---

### 4. `url-service` — Port 8082

**Responsibilities:** URL creation, listing, deactivation.

**Creation flow:**
1. Quota check via tenant-service (fail-open: allows if tenant-service is unavailable)
2. Fetch Snowflake ID from id-generator-service
3. Encode ID to 7-char Base62 code, or use custom alias (collision-checked)
4. `INSERT INTO urls`
5. Write-through cache prime: `SET url:{code} <JSON> EX <ttl>` in Redis
6. Increment tenant quota counter
7. Publish `URL_CREATED` event to `shortel.url-events` (Kafka, acks=1)

**Base62 encoding:**
```
Alphabet: 0-9 A-Z a-z  (62 chars)
Length:   always 7 characters
Capacity: 62^7 ≈ 3.5 trillion unique codes
```

**Endpoints:**
- `POST /api/v1/urls` — create
- `GET /api/v1/urls/{code}` — get metadata
- `GET /api/v1/urls?tenantId=1` — list by tenant
- `DELETE /api/v1/urls/{code}` — soft-deactivate (sets `is_active=0`, deletes Redis key)

---

### 5. `redirect-service` — Port 8083

The **hot path** — handles the highest traffic volume, optimised for minimum latency.

**Resolution flow:**
```
GET /{code}
    │
    ├─▶ Redis GET url:{code}
    │      HIT ──▶ parse JSON, check expiresAt
    │      MISS ─▶ MySQL SELECT WHERE short_code=? AND is_active=1
    │                └─▶ populate cache (SET url:{code} ... EX ttl)
    │
    ├─ Not found ──▶ 404
    ├─ Expired ────▶ 410 Gone
    ├─ PRIVATE + no Bearer ─▶ 401
    │
    ├─▶ @Async: publish click event to Kafka (acks=0, never blocks)
    │
    └─▶ HTTP 301 (PUBLIC) or 302 (PRIVATE) with Location: <longUrl>
```

**Key tuning choices:**
- Connection pool: **50 max connections** (vs 10-20 in other services)
- Redis timeout: **500ms** — fail-fast to MySQL on Redis slowness
- Kafka acks: **0** — fire-and-forget; analytics loss acceptable, redirect latency is not

---

### 6. `analytics-service` — Port 8084

**Responsibilities:** consume click events, maintain real-time counters, persist hourly aggregates.

**Pipeline:**
```
Kafka (shortel.clicks)
    │
    ▼
ClickEventConsumer
    │  parse event, derive visitorKey = IP + UA hash
    ▼
AnalyticsCounterService (Redis)
    ├─ INCR clicks:{urlId}         → click count (2-day TTL)
    └─ PFADD hll:{urlId} visitorKey → HyperLogLog unique estimate
    │
    ▼  (every 5 seconds)
AnalyticsFlushScheduler
    ├─ KEYS clicks:*               → find all active URL counters
    ├─ GETSET clicks:{urlId} "0"   → atomic read + reset
    ├─ PFCOUNT hll:{urlId}         → unique estimate
    └─ UPSERT analytics_hourly     → (url_id, hour_bucket, click_count+=, unique_count=)
```

**HyperLogLog** is used for unique visitor estimation — ~0.81% error at any cardinality, with O(1) memory regardless of traffic volume.

**Endpoints:**
- `GET /api/v1/analytics/{urlId}/stats` — live Redis counters (sub-second freshness)
- `GET /api/v1/analytics/{urlId}?from=&to=` — historical MySQL aggregates (hourly buckets)

---

### 7. `tenant-service` — Port 8085

**Responsibilities:** tenant CRUD, per-tenant quota tracking.

**Quota mechanism:**
- Counters stored in Redis: `quota:{tenantId}:urls` and `quota:{tenantId}:clicks`
- TTL is set to end of the current billing month on first increment
- Resets naturally at month boundary (Redis key expires)
- Plan limits:
  - `FREE`: 100 URLs, 10,000 clicks/month
  - `PAID`: unlimited (`Long.MAX_VALUE`)

---

## Data Flow Walkthroughs

### Creating a Short URL

```
Client
  │ POST /api/v1/urls  {"longUrl":"https://example.com","tenantId":1}
  │ Authorization: Bearer <jwt>
  ▼
API Gateway
  │ JwtAuthFilter validates token, injects X-User-Id header
  ▼
url-service
  │ 1. TenantClient → POST /api/v1/tenants/1/quota/check-url
  │                   → QuotaService: GET quota:1:urls (Redis) < 100? → true
  │ 2. IdGeneratorClient → GET /api/id/next
  │                   → SnowflakeIdGenerator.nextId() → 1234567890123
  │ 3. Base62Encoder.encode(1234567890123) → "aBc1234"
  │ 4. INSERT INTO urls (id=1234567890123, short_code="aBc1234", ...)
  │ 5. SET url:aBc1234 '{"longUrl":"https://example.com","visibility":"PUBLIC",...}' EX 86400
  │ 6. TenantClient → POST /api/v1/tenants/1/quota/increment-url
  │                   → INCR quota:1:urls (Redis)
  │ 7. kafkaTemplate.send("shortel.url-events", "aBc1234", "{event:URL_CREATED,...}")
  ▼
Client ← 201 Created  {id, shortCode:"aBc1234", longUrl, ...}
```

### Redirecting a Short URL

```
Client
  │ GET /aBc1234
  ▼
API Gateway
  │ Path matches /{code} pattern → public → skip JWT check
  │ CircuitBreaker(redirect-cb) → pass through
  ▼
redirect-service
  │ 1. GET url:aBc1234 (Redis, 500ms timeout)
  │       HIT  → parse JSON {"longUrl":"https://example.com","expiresAt":"","visibility":"PUBLIC",...}
  │       MISS → SELECT * FROM urls WHERE short_code='aBc1234' AND is_active=1
  │              → SET url:aBc1234 ... EX 86400  (populate cache)
  │ 2. expired? (expiresAt < now) → 410 Gone
  │ 3. visibility=PRIVATE + no Bearer → 401
  │ 4. @Async ClickEventProducer.publish("aBc1234", urlId, tenantId, request)
  │         → kafkaTemplate.send("shortel.clicks", ...) [acks=0, returns immediately]
  ▼
Client ← 301 Redirect  Location: https://example.com
```

### Analytics Pipeline

```
redirect-service
  │ Kafka PRODUCE → shortel.clicks
  │ {"urlId":123,"shortCode":"aBc1234","ip":"1.2.3.4","userAgent":"Mozilla/..."}

analytics-service ClickEventConsumer
  │ CONSUME ← shortel.clicks (group: analytics-group)
  │ visitorKey = "1.2.3.4:" + hex(ua.hashCode())
  │ INCR clicks:123           → 5 (click count, 2-day TTL)
  │ PFADD hll:123 visitorKey  → HyperLogLog update

  every 5 seconds: AnalyticsFlushScheduler
  │ KEYS clicks:*             → ["clicks:123", "clicks:456"]
  │ for clicks:123:
  │   count  = GETSET clicks:123 "0"  → 5 (atomic read+reset)
  │   uniques = PFCOUNT hll:123       → 3
  │   UPSERT analytics_hourly SET click_count+=5, unique_count=3
  │        WHERE url_id=123 AND hour_bucket='2026-04-12 10:00:00'

Client
  │ GET /api/v1/analytics/123/stats    → live Redis: {clicks:2, uniqueVisitors:1}
  │ GET /api/v1/analytics/123?from=... → MySQL: [{hourBucket, clickCount, uniqueCount}, ...]
```

---

## Database Schema

MySQL 8.0, single schema `shortel`.

```sql
-- Tenant accounts and billing plans
CREATE TABLE tenants (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  name       VARCHAR(255) NOT NULL,
  email      VARCHAR(255) NOT NULL UNIQUE,
  plan       ENUM('FREE','PAID') DEFAULT 'FREE',   -- FREE: 100 URLs / 10k clicks/month
  created_at DATETIME DEFAULT NOW(),
  is_active  TINYINT(1) DEFAULT 1
);

-- Shortened URLs
CREATE TABLE urls (
  id            BIGINT PRIMARY KEY,          -- Snowflake ID (NOT auto-increment)
  tenant_id     BIGINT NOT NULL,
  short_code    VARCHAR(10) NOT NULL UNIQUE, -- 7-char Base62 or custom alias
  long_url      TEXT NOT NULL,
  visibility    ENUM('PUBLIC','PRIVATE') DEFAULT 'PUBLIC',
  password_hash VARCHAR(60),                 -- BCrypt placeholder (Phase 2)
  expires_at    DATETIME,                    -- NULL = never expires
  created_at    DATETIME DEFAULT NOW(),
  created_by    BIGINT,                      -- user ID
  is_active     TINYINT(1) DEFAULT 1,        -- soft delete
  INDEX idx_tenant (tenant_id, created_at),
  INDEX idx_expires (expires_at)
);

-- Hourly click aggregates (flushed from Redis every 5 seconds)
CREATE TABLE analytics_hourly (
  url_id       BIGINT NOT NULL,
  hour_bucket  DATETIME NOT NULL,            -- truncated to hour boundary
  click_count  BIGINT DEFAULT 0,
  unique_count BIGINT DEFAULT 0,             -- HyperLogLog estimate
  PRIMARY KEY (url_id, hour_bucket)          -- composite PK = one row per URL per hour
);

-- Private URL ACL (structure in place, Phase 2)
CREATE TABLE url_access_list (
  url_id  BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  PRIMARY KEY (url_id, user_id)
);
```

### Redis Key Map

| Key Pattern | Type | Purpose | TTL |
|---|---|---|---|
| `url:{shortCode}` | STRING (JSON) | URL resolution cache | `expiresAt - now` or 24h |
| `clicks:{urlId}` | STRING (int) | Real-time click counter | 2 days |
| `hll:{urlId}` | HyperLogLog | Unique visitor cardinality | None |
| `quota:{tenantId}:urls` | STRING (int) | Monthly URL usage | End of billing month |
| `quota:{tenantId}:clicks` | STRING (int) | Monthly click usage | End of billing month |
| `refresh:{token}` | STRING | Refresh token → userId | 7 days |

---

## API Reference

### Authentication

```
POST /auth/token
Body:     {"email": "user@example.com", "password": "any"}
Response: {"accessToken": "eyJ...", "refreshToken": "uuid", "tokenType": "Bearer", "expiresIn": 900}

POST /auth/refresh
Body:     {"refreshToken": "uuid"}
Response: {"accessToken": "eyJ...", "tokenType": "Bearer", "expiresIn": 900}

POST /auth/logout
Body:     {"refreshToken": "uuid"}
Response: 204 No Content
```

### URL Management (requires `Authorization: Bearer <token>`)

```
POST /api/v1/urls
Body: {
  "longUrl": "https://example.com/very/long/path",
  "customAlias": "mylink",     // optional, 4-10 chars
  "visibility": "PUBLIC",       // PUBLIC | PRIVATE
  "expiresAt": "2026-12-31T23:59:59",  // optional
  "tenantId": 1,
  "createdBy": 42
}
Response 201: {id, shortCode, longUrl, visibility, createdAt, tenantId, ...}

GET  /api/v1/urls/{code}         → URL metadata
GET  /api/v1/urls?tenantId=1     → list all active URLs for tenant
DELETE /api/v1/urls/{code}       → soft-deactivate; invalidates Redis cache
```

### Redirects (public)

```
GET /{code}              → 301/302 with Location header, or 404/410/401
GET /resolve/{code}      → {"shortCode","longUrl","visibility","expired"} (no redirect)
```

### Analytics (requires JWT)

```
GET /api/v1/analytics/{urlId}/stats
Response: {"urlId": 123, "clicks": 42, "uniqueVisitors": 31, "source": "redis-realtime"}

GET /api/v1/analytics/{urlId}?from=2026-04-01T00:00:00&to=2026-04-12T23:59:59
Response: {"urlId": 123, "from": "...", "to": "...", "totalClicks": 500, "totalUniques": 380, "hourlyBuckets": [...]}
```

---

## Key Design Decisions

### 1. Redirect path never writes synchronously

The redirect hot path does zero synchronous DB writes. Click events are published to Kafka with `acks=0` (fire-and-forget) from an `@Async` method. If Kafka is unavailable, the redirect still succeeds and the click is silently dropped. **Analytics loss is acceptable; redirect latency is not.**

### 2. Snowflake IDs + Base62 = collision-free codes without coordination

Short codes are deterministically derived from Snowflake IDs rather than being random. This eliminates the birthday-paradox collision risk of random short codes and removes the need for DB round-trips to check uniqueness (except for custom aliases). The custom epoch (2023-11-14) keeps the Snowflake numbers small, which keeps the Base62 output at exactly 7 characters.

### 3. Cache-aside with write-through priming

On URL creation, url-service immediately writes to Redis (write-through). On redirect, Redis is checked first (cache-aside). This means the first redirect after creation is a cache hit, not a MySQL read. On cache miss, the redirect service populates the cache for subsequent hits.

### 4. Micro-batch analytics flush

Real-time counters in Redis (`INCR`) accumulate rapidly. A 5-second scheduler reads and atomically resets each counter (`GETSET clicks:{id} "0"`), then upserts the delta into MySQL. This decouples the analytics write rate from the redirect rate and allows MySQL to absorb traffic in controlled batches.

### 5. Fail-open quota checks

If tenant-service is unavailable during URL creation, the quota check returns `true` (allowed). This prevents the quota service from being a hard availability dependency for URL creation. The trade-off: quota overruns are possible during outages.

### 6. Circuit breaker tuning per route

The redirect circuit breaker (`redirect-cb`) uses a 20-request window and 60% threshold vs 10/50% for other routes. With higher redirect traffic, a 10-request window would trip on normal statistical variance. The lower recovery time (5s vs 10s) means the gateway retries faster after a redirect outage.

### 7. HyperLogLog for unique visitors

Redis `PFADD`/`PFCOUNT` uses HyperLogLog — a probabilistic data structure with ~0.81% error rate and constant memory (~12KB per key regardless of cardinality). This allows unique visitor estimation at scale without storing individual visitor identifiers.

---

## Running Locally

### Prerequisites

- Java 21 ([Eclipse Temurin](https://adoptium.net/temurin/releases/?version=21))
- Maven 3.9.x
- Docker Desktop with Compose V2

### Quick Start

```bash
# 1. Clone the repo
git clone git@github.com:BhanuPrakashBhukya/shortel-services.git
cd shortel-services

# 2. Create .env (copy the template below)
cat > .env <<EOF
MYSQL_ROOT_PASSWORD=shortel_root
MYSQL_USER=shortel
MYSQL_PASSWORD=shortel_pass
MYSQL_DATABASE=shortel
JWT_SECRET=ShortelDevSecretKeyMustBeAtLeast256BitsLongForHMACSHA256Algorithm
REDIS_PASSWORD=
EOF

# 3. Build and start everything
chmod +x build-and-run.sh
./build-and-run.sh

# 4. Verify all services are healthy
docker compose ps
```

All 7 services start in dependency order. The gateway is ready when all upstream services pass their health checks.

### Smoke Test

```bash
# Get a JWT token
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H "Content-Type: application/json" \
  -d '{"email":"demo@example.com","password":"any"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['accessToken'])")

# Create a short URL
RESULT=$(curl -s -X POST http://localhost:8080/api/v1/urls \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"longUrl":"https://github.com/BhanuPrakashBhukya/shortel-services"}')

SHORT_CODE=$(echo $RESULT | python3 -c "import sys,json; print(json.load(sys.stdin)['shortCode'])")
echo "Short code: $SHORT_CODE"

# Redirect
curl -v http://localhost:8080/$SHORT_CODE
# → HTTP 301, Location: https://github.com/BhanuPrakashBhukya/shortel-services
```

### Service Ports

| Service | Port |
|---|---|
| API Gateway (client entry point) | 8080 |
| Auth Service | 8081 |
| URL Service | 8082 |
| Redirect Service | 8083 |
| Analytics Service | 8084 |
| Tenant Service | 8085 |
| ID Generator Service | 8086 |
| MySQL | 3307 (host) → 3306 (container) |
| Redis | 6379 |
| Kafka | 9092 |

---

## Project Structure

```
shortel-services/
├── pom.xml                      ← Parent POM; Java 21, Spring Boot 3.2.4
├── docker-compose.yml           ← Full infrastructure + service orchestration
├── build-and-run.sh             ← Ordered build + startup script
├── init-db/init.sql             ← Database schema + seed data
│
├── api-gateway/                 ← Spring Cloud Gateway; JWT filter; circuit breakers
├── auth-service/                ← JWT issuance; refresh token management (Redis)
├── id-generator-service/        ← Snowflake 64-bit ID generation
├── tenant-service/              ← Tenant CRUD; quota tracking (Redis counters)
├── url-service/                 ← URL creation; Base62 encoding; cache priming
├── redirect-service/            ← Hot-path redirect; cache-aside; async click events
└── analytics-service/           ← Kafka consumer; Redis counters; MySQL flush scheduler
```

Each service follows the same internal layout:
```
<service>/
├── Dockerfile                               ← Multi-stage: Maven builder → JRE Alpine
├── pom.xml
└── src/main/
    ├── java/com/shortel/<service>/
    │   ├── <Name>Application.java           ← @SpringBootApplication entry point
    │   ├── controller/                      ← REST endpoints (@RestController)
    │   ├── service/                         ← Business logic (@Service)
    │   ├── repository/                      ← Data access (JpaRepository)
    │   ├── entity/                          ← JPA entities
    │   ├── dto/                             ← Request/response shapes
    │   ├── client/                          ← RestClient wrappers for outbound calls
    │   ├── consumer/                        ← Kafka @KafkaListener components
    │   ├── scheduler/                       ← @Scheduled tasks
    │   └── config/                          ← Spring configuration classes
    └── resources/application.yml           ← Service configuration
```

---

## Current Status & Roadmap

### Phase 1 (complete — this repo)
- [x] Core redirect flow with Redis cache-aside
- [x] URL creation with Snowflake IDs + Base62 encoding
- [x] JWT auth scaffolding (mock user store)
- [x] Multi-tenant quota tracking
- [x] Real-time analytics (Redis HyperLogLog + micro-batch flush)
- [x] Circuit breakers and fault isolation
- [x] Full Docker Compose orchestration

### Phase 2 (planned)
- [ ] Real user store + proper credential validation
- [ ] Password-protected URLs (BCrypt validation)
- [ ] URL password ACL enforcement (`url_access_list`)
- [ ] Click quota enforcement during redirects
- [ ] Dead-letter queue for failed Kafka messages
- [ ] GeoIP enrichment (MaxMind) + UA parsing
- [ ] Prometheus metrics + Grafana dashboards
- [ ] Distributed tracing (OpenTelemetry)
- [ ] `SCAN` cursor in analytics flush (replace `KEYS *`)

---

*Built with Java 21 · Spring Boot 3.2.4 · MySQL · Redis · Kafka*