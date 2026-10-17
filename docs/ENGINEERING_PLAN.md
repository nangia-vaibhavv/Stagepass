# StagePass engineering plan

## Baseline

The opened repository was empty and started with a new Git history. The current host has Git, Node.js 26/npm 11, and Docker Compose 5.6. Java and Maven are not installed on the host; the booking service builds and runs its Java 21/Maven toolchain inside its pinned Docker build image.

## Architecture and ownership

- `gateway` (Java/Spring Boot): public HTTP entry point, JWT verification, custom Redis-backed rate-limit strategies, and round-robin routing to booking nodes. Consistent-hash routing is deferred.
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
5. Stream delivery is at least once; consumers must be idempotent because a published event can be delivered more than once.

## Redis key conventions

- `stagepass:hold:{showId}:{seatId}` — token-valued expiring seat lock.
- `stagepass:presence:{showId}:{viewerId}` — expiring viewer heartbeat.
- `stagepass:waitlist:{showId}` — ordered, pseudonymous show-wide queue; a user requests 1–8 seats and a short Redis reservation assigns the next available seats.
- `stagepass:ratelimit:{policy}:{identityHash}` — strategy-specific limiter state; raw user IDs and IP addresses are not stored in the key.
- `stagepass:ratelimit:metrics` — aggregate allowed/blocked counters by policy.
- `stagepass:events` — domain event stream; consumer group `notification-workers`.
- `stagepass:presence:{showId}` — sorted set of hashed user IDs and last-heartbeat timestamps.

Key TTLs, payloads, and atomic scripts will be documented alongside their implementations.

## Delivery sequence

Complete one phase at a time: local infrastructure and catalog/auth; booking and payment; a small frontend preview pulled forward to explore the existing APIs; gateway and limiters; waitlist and realtime events; notifications; then return to complete the frontend; load tests; final documentation. Phase 4 hash-ring routing is currently deferred. Each completed phase gets a focused Git commit and a status update. If a local toolchain dependency is missing, source-level work may proceed, but its execution status will be reported as unverified.

## Initial implementation decisions

- Local defaults use a seeded development user and mock payment behavior; secrets remain environment supplied and `.env` is ignored.
- Compose will use one PostgreSQL and Redis instance, three booking-node replicas, and health checks.
- The Java services remain Spring Boot applications; Node services use Express and a maintained PostgreSQL/Redis client.
- No paid services, cloud infrastructure, broker, ready-made rate limiter, or hash-ring library will be introduced.

## Phase 2 decisions

- A booking node acquires each selected seat with Redis `SET NX PX` semantics and a per-booking owner token. Lua scripts release or extend only matching tokens.
- PostgreSQL rows hold booking state, transition history, payment attempts, and confirmation idempotency. A primary key on `(show_id, seat_id)` in `booking.confirmed_seats` is the final duplicate-sale guard.
- Booking state is implemented with state classes. Payment failure remains retryable while the Redis hold is active; each retry uses a new confirmation idempotency key.
- The mock payment API uses deterministic tokens. `pm_test_late_success` persists a callback job and the payment service retries delivery until the booking service acknowledges it. A stale success is compensated with an idempotent refund.
- The API smoke test deliberately restarts one booking node while a hold is active to verify recovery from shared PostgreSQL and Redis state.

## Frontend preview brought forward

- The initial React/Vite UI surfaces only behavior that already exists: login/registration, seeded show browsing, booking-node seat availability, holds, hold extension/cancellation, mock payment outcomes, and the user's bookings.
- Vite proxies `/api/auth`, `/api/catalog`, and `/api/booking` to the local Compose services, avoiding direct cross-origin requests from the browser. The preview uses booking node 1; the API state is shared across all three booking nodes.
- This is a learning and integration surface, not completion of Phase 7. Admin dashboard and full realtime seat updates await further frontend work.

## Phase 3 decisions

- The gateway is the public API entry at port 8080. It maps `/api/auth/login` and `/api/auth/register` to the auth service's `/auth/*` paths and strips `/api/catalog` or `/api/booking` before proxying. Booking requests are round-robin distributed for now; the consistent-hash owner router is Phase 4.
- The gateway validates the auth service's HS256 JWT signature and issuer for protected booking routes, then preserves the Authorization header for booking-node validation. Client-supplied forwarded-IP headers are stripped and are not used for limiter identity.
- Rate limiting is a Strategy interface with token-bucket and weighted sliding-window-counter implementations. Redis Lua scripts make state updates atomic across gateway instances. Policies and thresholds are configured in YAML.
- The weighted sliding-window counter estimates recent usage as `current + previous * (1 - elapsed/window)`. It is an approximation of a rolling window; token bucket gives burst capacity and continuous refill.
- Aggregate allowed/blocked counts are kept in a Redis hash and exposed only to admin JWTs. Response headers report rule, limit, remaining count, and reset time; HTTP 429 includes Retry-After.

## Phase 4 scope decision

- Consistent-hash ownership and fencing were removed from the current local implementation at the user's request. The gateway uses the Phase 3 round-robin router. The already-applied Flyway V3 migration is retained as an immutable local database migration; its ownership table is unused by the current code.

## Phase 5 decisions

- Waitlist membership is a Redis sorted set per show, ordered by an atomic sequence counter. Each pseudonymous user entry records the requested quantity (1–8). When enough seats are available, the allocator chooses the next available seats and reserves them in Redis for the 60-second offer window.
- `OfferNext`, `RetryCurrent`, `RoundRobin`, and `ReleaseToPool` implement queue selection behavior. Acceptance converts the waitlist seat reservations into the standard five-minute booking hold.
- Presence is a per-show sorted set of hashed user IDs and heartbeat timestamps. A viewer counts as active for 30 seconds; the API response is an approximate point-in-time count.
- Expired booking holds are transitioned by a periodic worker using PostgreSQL row locks and `SKIP LOCKED`, then released and offered to the waitlist if the seat is not confirmed.
- Booking events are written to a PostgreSQL outbox in the same transaction as lifecycle changes, then relayed to Redis Stream `stagepass:events` and Redis Pub/Sub for SSE. Delivery is at least once; SSE reconnect does not replay history, so clients refresh their seat snapshot.
- The wait estimate uses waitlist acceptances observed during the previous 15 minutes. With no samples, it assumes one acceptance per minute; this is a rough estimate.
