# StagePass engineering plan

## Baseline

The opened repository was empty. This project will start with a new Git history. The available environment currently has Git only; Java 21, Maven, Node.js 20, npm, Docker, and Docker Compose are unavailable.

## Architecture and ownership

- `gateway` (Java/Spring Boot): public HTTP entry point, JWT verification, custom Redis-backed rate-limit strategies, and consistent-hash routing to booking nodes.
- `booking-node` (Java/Spring Boot, three containers): seat holds, booking lifecycle, durable idempotency, payment callback handling, waitlist, SSE, and domain events.
- `catalog-service` (Node/Express): shows, venues, sections, seats, and availability read APIs; owns `catalog` schema.
- `auth-service` (Node/Express): registration and login; owns `auth` schema.
- `payment-service` (Node/Express): deterministic mock payment intents and callbacks; owns `payment` schema.
- `notification-worker` (Node): Redis Streams consumer, retry and dead-letter processing; owns `notification` schema.
- PostgreSQL is the durable source of truth, split into service-owned schemas. Redis holds ephemeral coordination state and stream data.
- `frontend` (React/Vite) uses only the gateway as its backend entry point.

## Critical invariants

1. A seat has at most one confirmed booking for a show; PostgreSQL constraints and a transaction enforce this regardless of Redis state.
2. Redis hold release and extension require the exact unexpired owner token and run atomically.
3. Confirmation idempotency is persisted and uniquely keyed in PostgreSQL; the key is bound to a canonical request digest.
4. Payment callbacks are idempotent, and late success after a terminal booking state triggers compensation rather than a second confirmation.
5. Show ownership metadata carries an epoch; state-changing requests must validate the current epoch against durable/shared metadata.
6. Stream delivery is at least once; notification persistence makes duplicate deliveries harmless.

## Redis key conventions

- `stagepass:hold:{showId}:{seatId}` — token-valued expiring seat lock.
- `stagepass:presence:{showId}:{viewerId}` — expiring viewer heartbeat.
- `stagepass:waitlist:{showId}` — ordered waitlist entries.
- `stagepass:ratelimit:{policy}:{identity}` — strategy-specific limiter state.
- `stagepass:ownership:{showId}` — current owner and fencing epoch when ownership coordination uses Redis.
- `stagepass:events` — domain event stream; consumer group `notification-workers`.

Key TTLs, payloads, and atomic scripts will be documented alongside their implementations.

## Delivery sequence

Complete one phase at a time: local infrastructure and catalog/auth; booking and payment; gateway and limiters; hash ring and fencing; waitlist and realtime events; notifications; frontend; load tests; final documentation. Each completed phase gets a focused Git commit and a status update. If a local toolchain dependency is missing, source-level work may proceed, but its execution status will be reported as unverified.

## Initial implementation decisions

- Local defaults use a seeded development user and mock payment behavior; secrets remain environment supplied and `.env` is ignored.
- Compose will use one PostgreSQL and Redis instance, three booking-node replicas, and health checks.
- The Java services remain Spring Boot applications; Node services use Express and a maintained PostgreSQL/Redis client.
- No paid services, cloud infrastructure, broker, ready-made rate limiter, or hash-ring library will be introduced.
