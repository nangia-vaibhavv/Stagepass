# StagePass Project Handoff

This document is the current working brief for a new assistant continuing StagePass. Read it before changing code. The user supplied a larger original specification; this handoff summarizes the current goal, decisions, implementation state, and how to continue without losing work.

## Current location and Git state

- Repository: `/Users/vaibhavnangia/Documents/Projects/stagepass`
- Branch: `master`
- `HEAD` and `origin/master` currently point to `233f670` (`adding waitlist changes`). The Phase 5 waitlist changes are committed and pushed.
- The public deployment work began locally. The user has now explicitly asked to push the reviewed hosting changes and deploy them to Vercel; keep generated credentials out of Git and do not make unrelated commits.
- The user's local `.env` is ignored by Git. Never copy secret values into docs or chat.
- Phase 4 consistent-hash routing was implemented and then explicitly removed at the user's request. The gateway is back to Phase 3 round-robin routing. Do not reintroduce Phase 4 unless asked.
- `booking-node/db/migration/V3__show_ownership.sql` is retained locally because that migration was already applied to the user's database. Its ownership table is unused. Do not delete or rewrite applied migrations.

## Product and architecture goal

StagePass is a local, portfolio-scale ticket booking platform for high-contention live-show sales. It uses a monorepo with React/Vite frontend, Java/Spring Boot gateway and three booking-node replicas, Node/Express catalog/auth/payment services, PostgreSQL, and Redis. PostgreSQL is the durable booking source of truth; Redis coordinates seat holds, rate limits, presence, the waitlist, and live events. Docker Compose is the supported local run path.

The user wants a usable UI for learning and testing, a shareable public demo URL, simple explanations of what each phase accomplishes, and practical browser test steps. They chose normal React for the frontend. Keep new work reviewable; don't push changes without being asked.

The broad original roadmap includes:

1. Infrastructure, catalog, authentication.
2. Booking lifecycle, Redis seat locking, idempotent payment flow and compensation.
3. API gateway, JWT validation, round-robin booking routing, custom Redis-backed token-bucket and sliding-window limiters.
4. Consistent hash ownership/fencing — explicitly removed/deferred by the user.
5. Waitlist, live presence, SSE, and domain events — active work.
6. Notification worker with Redis Stream consumer groups, retries, and dead letters — not started.
7. Complete frontend/admin dashboard — MVP preview exists; more UI remains.
8. k6 contention/rate-limit/load scenarios — not started.
9. Final hardening and documentation.

See [ENGINEERING_PLAN.md](ENGINEERING_PLAN.md), [IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md), and [PHASE5_API.md](PHASE5_API.md) for more detail.

## New deployment request and current deployment state

The user wants to make StagePass publicly accessible now at little or no cost, inspect the live PostgreSQL data, and continue adding features after deployment. They corrected the earlier assumption that Vercel was frontend-only: current official Vercel docs support HTTP container images, including Java, and a multi-service Beta. The latest direction is to investigate deploying the frontend and HTTP backend services on Vercel, with external managed PostgreSQL/Redis free tiers, keeping OCI as fallback if needed. `docs/DEPLOYMENT.md` records the caveats. This is still a portfolio demo, not real ticket sales.

- Deployment target: Vercel Services for frontend/backend containers plus Vercel Daemons for background workers, using Neon PostgreSQL and Upstash Redis free tiers. Neon `stagepass-prod` exists in Singapore on the Free plan. Upstash Redis and the Vercel app project are not yet connected. Services and Daemons are Beta and Hobby quotas are finite. OCI Compose remains a fallback; never silently select a paid resource.
- Root `vercel.json` defines the Vercel Services/Daemons layout. `frontend/vercel.json` remains for frontend-only hosting. Gateway CORS reads `CORS_ALLOWED_ORIGINS`; the full stack routes frontend API requests same-origin.
- Neon `stagepass-prod` was provisioned through the Vercel Marketplace on its Free plan in Singapore, with Neon Auth and Realtime disabled. No Upstash Redis or Vercel app project has been connected; no public URL exists. The Vercel account is signed in on the browser and shows the Hobby plan. The Oracle VM creation form was left unsubmitted; no VM was created. The user explicitly authorized pushing the deployment changes as part of hosting.
- Vercel is not yet a drop-in fit for the current runtime topology. `payment-service` polls callbacks every 500 ms; Spring booking nodes schedule hold expiry, outbox relay, and waitlist-offer expiry, and serve SSE. These background jobs need an always-running worker model; ordinary HTTP Function calls or Hobby Cron are not a safe replacement. Vercel Daemons may be an option but have finite Hobby active-CPU quota and cannot receive inbound HTTP. Review `docs/DEPLOYMENT.md` before more deployment changes.
- The Vercel deployment layout is implemented locally: one booking HTTP service replaces three Compose replicas; booking expiry/outbox/waitlist cleanup run in a non-web Spring Daemon; delayed payment callback delivery is a separate Node Daemon; Vercel rewrites expose only frontend/API plus the authenticated payment callback route. `scripts/migrate-managed-db.sh` migrates all four schemas and disables the seeded production demo admin. Vercel Daemon quota is 10 Active CPU-hours/month on Hobby.
- Deployment files added locally: `deploy/docker-compose.production.yml`, `deploy/Caddyfile`, `deploy/.env.production.example`, `frontend/Dockerfile.production`, `frontend/deploy/nginx.conf`, and `auth-service/db/migration/V2__optionally_disable_demo_admin.sql`.
- The production Compose overlay removes public host ports from app services, retains PostgreSQL only on VM loopback for an SSH tunnel, builds a static React/Nginx image, and adds Caddy on ports 80/443. Set `DISABLE_DEMO_ADMIN=true` before the first production migration; migration V2 removes the known seeded admin. The frontend no longer pre-fills or displays that public demo password.
- Deployment validation completed locally before latest CORS changes: merged production Compose `config -q` passed; all application images built with the production overlay; gateway Maven/JUnit build passed with 7 tests (3 for trusted proxy client-IP handling); production auth migrations passed against an isolated temporary PostgreSQL database and confirmed the seeded demo admin was removed. Latest Vercel frontend build and Compose config pass; run a gateway build/test for CORS changes when Docker is available. The app has not been deployed to a cloud VM and has no public URL. The auth V2 migration has not been applied to production. Keep `.env.production` private and never ask the user to paste secrets here.
- Gateway IP rate-limiting ignores forwarded headers by default. Production overlay turns on `TRUST_PROXY_HEADERS` only behind Caddy; it uses the rightmost valid forwarded address and falls back to the socket address. No external proxy trust is enabled in the local Compose config.
- To inspect hosted data, use DBeaver over SSH forward `15432 -> VM loopback 5432`; database/schema/table details are in `docs/DEPLOYMENT.md`. Make and copy off-VM database backups; do not `docker compose down -v`.
- The user wants ongoing feature updates. First path is reviewed push then `git pull --ff-only` on the VM, backup, and Compose rebuild. Add a reviewed CI/CD deploy workflow later; do not add a live SSH deploy key workflow without user approval.

## What is implemented locally now

### Earlier phases

- Compose stack for PostgreSQL, Redis, auth, catalog, three booking nodes, payment mock, gateway, and frontend.
- Seeded shows/venues/seats and local demo authentication.
- Redis `SET NX PX` seat holds and owner-token release/extension.
- PostgreSQL booking state, confirmed-seat uniqueness, transaction history, payment idempotency, async callback/retry, and late-payment compensation.
- Gateway JWT validation and custom token-bucket/sliding-window limiters with Redis Lua scripts and admin metrics.
- React/Vite UI for shows, seat map, auth, booking holds, payment simulation, bookings, extend/cancel.

### Current Phase 5 backend

- Presence heartbeat/count, hashed viewer identifiers, 30-second active window.
- Booking lifecycle outbox to Redis Streams and Redis Pub/Sub, then per-show SSE events through the gateway.
- A **show-wide** waitlist instead of a seat-specific queue. A user requests 1–8 seats. The allocator selects any next available seats for that show and reserves them in Redis for 60 seconds. Acceptance converts those reservations to the normal five-minute booking hold.
- Queue selection implementations: `OfferNext`, `RetryCurrent`, `RoundRobin`, `ReleaseToPool`.
- Event stream contains no user identifiers. Offer details are obtained through the authenticated caller's `/waitlist/me` endpoint. SSE is live only; it does not replay missed events on reconnect.
- See the API table in `docs/PHASE5_API.md`.

### Current Phase 5 frontend and demo inventory

- Selecting a show displays an **Audience & waitlist** panel with live viewer count, quantity selector, queue/offer status, accept action, and recent live events.
- Clicking **Hold selected seats** while signed out opens a sign-in/create-account prompt. The user's seat selection is retained while they authenticate.
- A dedicated **Boundary Waitlist Demo** show was added: 60 seats total, 57 confirmed by a fixture booking, and exactly three available (A-1, A-2, A-3). The fixture is created by catalog migration V2 plus booking migration V5. Booking migration waits for the catalog migration.
- Use two different browser profiles/accounts for multi-user tests; separate tabs in the same browser profile share localStorage and the same login.

## How to run locally

From the repository root:

```sh
docker compose up --build
```

Open <http://localhost:5173>. The frontend proxies API requests through the gateway on port 8080. `docker compose up --build -d` runs in the background; `docker compose ps` shows health.

The local development login shown in the UI is `demo@stagepass.local` / `stagepass-demo`. For waitlist testing, register two distinct local accounts instead.

## Browser walkthrough for the latest feature

To prove that a show-wide request for two arbitrary seats becomes an offer when seats are released:

1. Open <http://localhost:5173> in a regular window and a private window.
2. Create/sign in as account A in the regular window and account B in the private window. Use different accounts.
3. In A, select **Boundary Waitlist Demo**. The only available seats should be A-1, A-2, and A-3. Select all three and click **Hold selected seats**.
4. In B, select the same show. Set **Number of seats** to `2` and join the show-wide waitlist while A has all three seats held.
5. In A, cancel the hold from **Bookings**.
6. B should see the live offer with any two of the released seats. Click **Accept offer** within 60 seconds. This creates a normal five-minute hold. Complete mock payment from **Bookings** if desired.

To see the login prompt, sign out, select an available seat, and click **Hold selected seats**. Choose sign-in or account creation in the prompt.

The wait estimate is approximate. A seat offer reserves seats for 60 seconds; accepting it turns that reservation into a five-minute booking hold. If enough seats are already free when someone joins, the allocator may offer them immediately.

## Verification already completed

- `npm --prefix frontend run build` passed.
- `node --check scripts/phase5-smoke.mjs` passed.
- `docker compose config -q` passed.
- `docker compose build booking-node-1` passed; Maven/JUnit ran 9 tests (5 booking lifecycle/state, 4 offer selection), all passing. The three booking-node images have been rebuilt from the same source.
- `node scripts/phase5-smoke.mjs` passed against the local gateway. It checked presence, show-wide quantity-two waitlist, admin offer mode, holding and releasing all three demo seats, receiving `waitlist.offered` through gateway SSE, accepting the two-seat offer, and cancelling the test hold.
- Inventory check returned HTTP 200 and showed 60 total / 57 confirmed / 3 available / 0 held after smoke cleanup.
- `docker compose ps` showed the services healthy at last check.
- Smoke runs create local test user/booking history that remains in PostgreSQL. The Boundary demo inventory stayed at 57 confirmed and 3 available after cleanup.

## Important files changed or added in this local work

- `frontend/src/main.jsx`, `frontend/src/styles.css`: login prompt and Phase 5 UI.
- `booking-node/src/main/java/io/stagepass/booking/BookingController.java`: presence, SSE, show-wide queue and offer endpoints.
- `booking-node/src/main/java/io/stagepass/booking/BookingService.java`: convert a reserved multi-seat offer into a standard hold.
- `booking-node/src/main/java/io/stagepass/booking/RealtimeService.java`: presence, show-wide queue allocation, short-lived seat reservations, offers and live events.
- `booking-node/db/migration/V4__realtime_outbox.sql`, `V5__boundary_waitlist_inventory.sql`, plus the already-applied local V3 migration.
- `catalog-service/db/migration/V2__boundary_waitlist_demo.sql`: demo show and seat layout.
- `docker-compose.yml`: migration ordering for the demo inventory.
- `gateway/.../GatewayController.java`, `SecurityConfig.java`: SSE forwarding/security changes from the earlier Phase 5 work.
- `scripts/phase5-smoke.mjs`: local regression smoke for a two-seat global queue.
- `docs/PHASE5_API.md`, `README.md`, `docs/ENGINEERING_PLAN.md`, `docs/IMPLEMENTATION_STATUS.md`: updated contract and instructions.

Use `git status --short` before editing. There may be other local Phase 5 changes in the working tree beyond the list above; preserve them.

## Current limitations and next work

- Phase 5 is in progress, not production-ready. Broader multi-node concurrency, offer-expiry, restart/recovery, and failure-path checks remain useful next steps.
- The notification worker and k6 load-test scenarios have not been implemented.
- The frontend admin dashboard and full replay/reconnect behavior for SSE are not implemented.
- The smoke script registers a temporary holder account and leaves historical database records; don't wipe the user's volumes to clean up.
- PostgreSQL and Redis are shared local services. The project demonstrates distributed coordination; it does not establish internet-scale capacity.
- Keep Phase 4 removed. Continue with Phase 5 hardening or, when the user asks, move to notifications/remaining roadmap work.

## Instructions to the next assistant

1. Read this file and inspect `git status` first.
2. Treat all current modifications as user-reviewable local work. Do not reset, commit, or push unless the user explicitly asks.
3. Keep using React for frontend work and update the API contract/docs with behavioral changes.
4. Explain feature testing in plain, concrete browser steps. Distinguish automated checks from steps the user must perform in their browser.
5. Don't claim Phase 5 or the full project is finished; report the remaining work above accurately.

---

## Appendix: Original project specification

The following is the user's original project brief, included so a fresh assistant can recover the intended scope without access to this chat's attachment. Apply the later decisions documented above where they supersede the brief (especially: Phase 4 consistent hashing was removed at the user's request; do not commit or push unless the user explicitly asks; React is required; the latest user-authored UI requirements are recorded above).

# StagePass — End-to-End Distributed Ticket Booking Platform

## 1. Your Role and Mission

Act as a senior backend engineer, distributed-systems architect, and implementation partner.

Build **StagePass**, an end-to-end ticketing platform for live music shows with a high-contention flash-sale booking flow.

This is a serious portfolio project for a software developer with approximately 3.5 years of professional experience. The primary objective is to demonstrate strong backend engineering, concurrency control, distributed systems, algorithms, reliability, testing, and system-design decision-making.

Work directly in the opened repository. Create and modify actual files, run commands, execute tests, fix failures, and commit completed phases to Git.

Do not merely provide code snippets, pseudocode, or implementation instructions.

Work autonomously. Make sensible engineering decisions without repeatedly asking for clarification. Ask questions only when genuinely blocked by missing credentials, destructive operations, or a decision that cannot reasonably be inferred.

Keep conversational updates concise. Put technical details, commands, API documentation, diagrams, test results, and design explanations in the repository README and supporting documents.

## 2. Core Engineering Principles

Prioritize the following, in order:

1. Correctness under concurrency.
2. Reliable booking and payment workflows.
3. Explicit state machines and idempotency.
4. Custom implementations of the required algorithms.
5. Distributed routing and safe ownership changes.
6. Failure recovery and observability.
7. Reproducible load testing and measurable results.
8. A clean, usable frontend.
9. Maintainable code, meaningful tests, and documentation.

Do not optimize for the number of services or lines of code.

A small, correct, thoroughly tested implementation is better than a large collection of unreliable services.

Do not claim a feature works until it has been implemented and verified to the extent possible in the available environment.

## 3. Hard Constraints

- Everything must use free and open-source software, libraries, and tooling.
- No paid infrastructure or external paid services.
- The application must run locally with:

  `docker compose up --build`

- Provide seed data and useful local development defaults.
- Use a monorepo.
- Use Git commits after each meaningful, completed phase.
- Write the core algorithms manually.
- Do not use Bucket4j, Redisson, Resilience4j rate limiters, or a ready-made consistent-hashing library.
- Do not replace required custom algorithms with libraries that hide their implementation.
- Use established libraries for ordinary framework functionality, database access, password hashing, HTTP, serialization, and testing.
- Never commit secrets, real credentials, or populated `.env` files.
- Pin or constrain dependency versions and use reproducible builds.
- Do not introduce Kubernetes, Kafka, paid cloud infrastructure, or other unnecessary infrastructure.
- Do not use a distributed database when PostgreSQL and Redis are sufficient for the intended demonstration.

## 4. Technology Stack

### Java services

- Java 21
- Spring Boot 3
- Maven
- JUnit 5
- Spring testing utilities where appropriate

### Node.js services

- Node.js 20
- Express
- npm
- A maintained PostgreSQL driver or query library
- A maintained Redis client
- Automated tests using Node's test runner or another lightweight testing framework

### Infrastructure

- PostgreSQL: one local instance, separate schema per service
- Redis: locks, rate-limit state, presence, sorted sets, lists, and Streams
- Flyway SQL migrations for PostgreSQL schemas
- Docker and Docker Compose

### Frontend

- React
- Vite
- Plain CSS
- No UI component library required
- No state-management library required

### Testing and documentation

- k6 for load testing
- Mermaid for architecture and sequence diagrams
- Git for version control

Use compatible, maintained dependency versions and document any deviations from this stack.

## 5. Repository Structure

Inspect the repository before making changes. Preserve existing useful work and history.

Use this structure or an equivalent clearly documented organization:

stagepass/
  gateway/
  booking-node/
  catalog-service/
  auth-service/
  payment-service/
  notification-worker/
  frontend/
  infra/
    postgres/
    redis/
  loadtest/
  docs/
  docker-compose.yml
  .env.example
  .gitignore
  README.md

The gateway and booking-node are Java applications. The remaining four backend services are Node.js applications.

Run three booking-node instances in Docker Compose.

Use environment variables for service URLs, database configuration, Redis configuration, secrets, and runtime settings. Supply safe local defaults for non-secret configuration.

Use separate PostgreSQL schemas and separate migration locations for each service. Document service ownership of tables.

Do not introduce an API gateway library that replaces the custom rate limiter or consistent-hash router.

## 6. Product Overview

StagePass allows users to:

- Register and log in.
- Browse live music shows.
- View venue layouts and seat availability.
- Participate in a scheduled flash sale.
- Hold available seats temporarily.
- Pay through a mock payment service.
- Receive confirmed bookings or handle payment failures.
- Join a waitlist when seats are unavailable.
- Receive offers when seats are released.
- Observe live seat availability and audience counts.
- View their bookings.

An admin dashboard demonstrates backend infrastructure and operational behavior.

Seed at least three shows, including one scheduled flash-sale show.

Provide realistic venues, sections, rows, seats, prices, and show dates. Make the seed data deterministic.

## 7. Gateway — Java and Spring Boot

The gateway is the single public API entry point on port 8080.

### 7.1 Request routing

- Route `/shows/{showId}/...` booking traffic to the booking-node that owns the show according to the consistent-hash ring.
- Proxy catalog, authentication, and other appropriate routes to their Node.js services.
- Validate JWTs for protected routes.
- Preserve required headers, query parameters, HTTP methods, and response status codes.
- Preserve streaming responses for SSE endpoints.
- Apply connection and request timeouts.
- Avoid unbounded request buffering.

Define a clear route table and document it.

### 7.2 Custom rate limiter

Implement the Strategy pattern with:

- `RateLimiter` interface.
- `TokenBucketRateLimiter`.
- `SlidingWindowCounterRateLimiter`.

Do not use a ready-made rate-limiting library.

Store distributed rate-limit state in Redis and implement atomic updates with Lua scripts.

Requirements:

- Configurable policies per user, IP, and endpoint through YAML or equivalent configuration.
- Stricter limits for seat-hold and confirmation endpoints.
- Configurable capacity, refill rate, window, and request limits.
- HTTP 429 responses.
- Correct `Retry-After` behavior.
- `X-RateLimit-*` response headers.
- Allowed and blocked counters by rule.
- An admin endpoint exposing aggregate counters.

Test concurrent requests, boundary conditions, refill behavior, sliding-window calculations, and key isolation.

Use a clearly documented algorithm for the sliding-window counter, including its approximation limitations.

Never trust a client-supplied identity header unless it is inserted or validated by a trusted component.

### 7.3 Consistent-hash router

Implement the consistent-hash ring manually.

Requirements:

- A documented hash function, such as an inlined MurmurHash variant.
- Approximately 150 virtual nodes per physical booking node by default.
- A sorted ring and efficient lookup of the first eligible point clockwise from a show's hash.
- Deterministic routing for the same show ID.
- Node health checks.
- Skipping unhealthy nodes.
- Admin APIs to list the ring, add a node, and remove a node logically.
- Physical containers remain running during logical ring changes.
- Report the number and percentage of shows whose owners changed after each rebalance.
- Compare ownership before and after topology changes using a deterministic show-key dataset.
- Provide a ring visualization or table in the admin UI.

Test deterministic mapping, distribution, wraparound lookup, empty-ring behavior, node addition, node removal, and minimal movement.

The ring is routing infrastructure, not a correctness guarantee by itself.

### 7.4 Safe ownership transfer

Implement controlled ownership changes.

- Track the current owner and an ownership epoch or fencing token for each show.
- Prevent stale owners from performing critical writes.
- Ensure requests, callbacks, and subsequent events reach the current owner.
- Define behavior when a node fails during an ownership change.
- Make ownership metadata durable or reconstructible after restart.
- Reject or safely retry operations from stale owners.

A shared PostgreSQL instance and Redis instance are acceptable simplifications.

Do not claim that hashing alone provides single-writer safety.

## 8. Booking Node — Java and Spring Boot

Run three instances from the same application image, with configurable node IDs.

Each node serves the shows assigned to it by the gateway.

### 8.1 Seat locking

Implement seat holds using Redis.

Requirements:

- Use atomic `SET key token NX PX ttl` semantics.
- Default hold TTL: five minutes.
- Every hold has a unique owner token and a booking/hold identity.
- Release and extension must verify the token atomically using Lua.
- A user must not release or extend another user's hold.
- Handle expired holds and stale clients.
- Publish appropriate seat-state changes.
- Avoid relying exclusively on Redis locks for final booking correctness.

Persist the booking and seat state in PostgreSQL.

Enforce a unique constraint on `(show_id, seat_id)` for confirmed bookings, using a schema that also supports the intended booking lifecycle.

Use database transactions and appropriate concurrency control when confirming bookings.

The database must remain the final defense against duplicate confirmed bookings.

### 8.2 Booking lifecycle and State Pattern

Implement the lifecycle with actual state classes.

Suggested components:

- `BookingSession`
- `BookingState`
- `BrowsingState`
- `SeatsHeldState`
- `PaymentPendingState`
- `PaymentFailedState`
- `ConfirmedState`
- `ExpiredState`
- `CancelledState`

Each state implements the relevant actions:

- `selectSeats`
- `pay`
- `cancel`
- `timeout`

Avoid large conditional chains based on the current state.

Illegal transitions must throw typed, meaningful domain errors and map to appropriate HTTP responses.

Persist the current state and required transition metadata.

Use optimistic locking, row-level locking, or an equivalent concurrency mechanism to prevent concurrent requests from independently applying conflicting transitions.

Persist transition history if useful for debugging and demonstration.

Recover active sessions after restart.

Test every legal transition, every illegal transition, expiration, cancellation, payment failure, duplicate requests, restart recovery, and concurrent transition attempts.

### 8.3 Booking confirmation and idempotency

Implement a durable idempotency mechanism.

- Accept an `Idempotency-Key` for confirmation requests.
- Persist idempotency records in PostgreSQL.
- Enforce uniqueness at the database level.
- Return the previous result when the same key and request are replayed.
- Reject reuse of the same key with a different request payload.
- Define behavior for concurrent duplicate requests and requests still processing.
- Persist the final response or enough information to reproduce it.
- Make retries safe across application restarts.

Do not depend exclusively on an in-memory cache or an expiring Redis key for durable idempotency.

### 8.4 Payment workflow and stale callbacks

The Node.js payment service is a mock payment processor.

The workflow must explicitly handle the following race:

A seat hold expires while payment is in progress, a new user acquires the seat, and the original payment succeeds afterward.

A stale callback must never confirm a seat that is no longer owned by the original booking.

Requirements:

- Associate payment attempts with booking ID, hold identity, and idempotency key.
- Verify current ownership and booking state before confirmation.
- Make payment callbacks idempotent.
- Prevent a delayed callback from reviving an expired or cancelled booking.
- Handle payment success after hold expiry through a compensating action, such as recording a refund request.
- Define behavior when payment succeeds but the booking database transaction fails.
- Record payment and booking outcomes durably.

Use a clear transaction boundary for confirmation.

For reliable event publishing, implement a transactional outbox or document and test an equivalent recovery strategy. Do not assume a PostgreSQL commit and a Redis publish are one atomic operation.

### 8.5 Waitlist offer rotation

Implement waitlist cursor behavior using Redis lists or sorted sets, with a documented data model.

Offer modes must be represented as State classes:

- `OfferNext`: advance after a 60-second timeout.
- `RetryCurrent`: offer again to the same user.
- `RoundRobin`: wrap to the start.
- `ReleaseToPool`: return the seat to general sale.

Implement:

- A cursor abstraction.
- A common offer-mode interface.
- Separate classes for each mode.
- Runtime mode switching per show through an admin API.
- Safe cursor updates under concurrency.
- Offer expiry and acceptance.
- Duplicate-offer prevention.
- Recovery after node restart.

The cursor must delegate behavior to the selected mode instead of containing a large conditional statement for each mode.

Do not expose personal information in public waitlist APIs.

### 8.6 Live presence

Implement heartbeats every 10 seconds.

Use a Redis sorted set per show:

- Member: pseudonymous session or viewer ID.
- Score: last heartbeat timestamp.
- Active viewer window: last 30 seconds.

Requirements:

- Report active viewer counts.
- Evict or ignore stale entries.
- Make repeated heartbeats idempotent.
- Prevent duplicate counting of the same session.
- Use TTLs or cleanup jobs as appropriate.
- Avoid exposing usernames or personal information.
- Support queue position and waitlist position.

Estimate wait time using a documented recent admission-rate calculation. Make it clear that the estimate is approximate.

### 8.7 Server-Sent Events

Implement SSE endpoints for show-level updates.

Events include:

- Seat held.
- Seat booked.
- Seat released.
- Viewer count updated.
- Waitlist offer created.
- Waitlist offer expired.

Requirements:

- The current show owner serves its SSE stream.
- Clients reconnect using supported event IDs or equivalent replay semantics where practical.
- Handle disconnected clients and bounded queues.
- Do not let slow consumers cause unbounded memory growth.
- Ensure ownership changes do not permanently strand connected clients.
- Document whether missed events can be replayed or clients must refresh the snapshot.

Use SSE rather than WebSockets unless a specific requirement makes WebSockets necessary.

### 8.8 Domain events

Publish events such as:

- `booking.confirmed`
- `booking.expired`
- `seat.released`
- `waitlist.offered`

Use Redis Streams.

Events should contain event ID, event type, aggregate ID, timestamp, schema version, and relevant metadata.

Define an event naming convention and document delivery guarantees.

## 9. Catalog Service — Node.js and Express

Implement:

- Venue management.
- Show management.
- Seat layouts.
- Sections, rows, and seats.
- Price tiers.
- Show search.
- Pagination.
- Show details.
- Admin-only show creation.
- Scheduled on-sale timestamps.

Use PostgreSQL and migrations.

Include deterministic seed data for at least three shows.

Expose only the fields needed by the frontend and booking service.

Document API contracts and validate request payloads.

## 10. Authentication Service — Node.js and Express

Implement:

- Registration.
- Login.
- Password hashing using bcrypt.
- JWT access tokens.
- Protected routes and role checks.
- Input validation.
- Safe error responses.
- Basic login rate limiting through the gateway.

Use a configurable JWT secret supplied through environment variables.

Never store plaintext passwords or commit secrets.

Include an admin role for demonstration purposes.

Do not create insecure production-looking authentication flows merely to simplify the demo. Document any intentional local-development simplifications.

## 11. Payment Service — Node.js and Express

Implement a mock payment processor.

Requirements:

- Accept payment requests.
- Support an idempotency key.
- Resolve asynchronously after approximately one to three seconds.
- Simulate approximately 20% payment failure.
- Support retries.
- Deliver callbacks to the appropriate current booking owner.
- Include booking ID, payment ID, attempt ID, and idempotency key in the callback.
- Authenticate internal callbacks using a local shared secret or equivalent mechanism.
- Persist payment attempts and outcomes.
- Handle duplicate callbacks and callback delivery failures.
- Support a refund or compensation record for late payment success after booking expiry.

Make failure probability and latency configurable for deterministic testing.

Avoid external payment providers.

## 12. Notification Worker — Node.js

Consume Redis Streams using consumer groups.

Requirements:

- Consumer-group processing.
- Idempotent notification creation.
- Mock email/SMS behavior through logs and database records.
- Retry with exponential backoff and bounded attempts.
- Dead-letter stream after a configurable number of failures.
- Recovery of pending messages abandoned by crashed consumers.
- Safe acknowledgement ordering.
- Duplicate delivery handling.
- Metrics for processed, retried, failed, and dead-lettered notifications.

Persist notification records in PostgreSQL.

Provide admin APIs for counts and recent failure details, without exposing sensitive user information.

Document the delivery semantics and the difference between at-least-once delivery and exactly-once side effects.

## 13. Frontend — React and Vite

Build a simple, attractive, functional frontend.

Use:

- Neutral colors.
- One accent color.
- System fonts.
- Simple cards and forms.
- Responsive layouts.
- Plain CSS.
- Minimal animations.

Do not spend disproportionate time on visual polish.

### Pages

1. Login and registration.
2. Shows list.
3. Show detail and seat map.
4. My bookings.
5. Admin dashboard.

### Show list

- Display show cards.
- Show venue and date.
- Display sale status.
- Show a countdown for the flash-sale show.

### Show page

- Render a seat-grid layout.
- Distinguish available, held, booked, and current-user-held seats.
- Update state through SSE.
- Show active viewer count.
- Allow seat selection and seat holds.
- Display the hold countdown.
- Provide a payment action and show its result.
- Allow waitlist joining.
- Display queue position and approximate ETA.
- Display waitlist offers with acceptance countdowns.

### My bookings

Show booking status, seats, and relevant payment outcome.

### Admin dashboard

Display:

- Ring nodes and virtual-node counts.
- Shows owned per node.
- Add/remove node controls.
- Number and percentage of shows moved after rebalancing.
- Rate-limit allowed/blocked counters by rule.
- Waitlist offer-mode switcher.
- Notification counts.
- Retry and dead-letter queue counts.
- Basic health status.

Do not fake backend metrics in the UI.

The frontend should display real API responses. Label simulated data and approximate estimates where applicable.

## 14. Docker Compose and Local Development

The complete application must start with:

`docker compose up --build`

Include:

- PostgreSQL.
- Redis.
- Gateway.
- Three booking-node instances.
- Catalog service.
- Auth service.
- Payment service.
- Notification worker.
- Frontend.

Requirements:

- Health checks.
- Appropriate startup dependencies.
- Restart policies where useful.
- Persistent development volumes.
- Environment configuration.
- Database migrations.
- Seed data.
- Useful container logs.
- Internal service networking.
- Correct port exposure.
- Graceful shutdown where practical.

Do not assume that container startup order means a dependency is ready.

The startup process should tolerate transient database and Redis readiness failures.

Avoid hardcoded container IP addresses.

Provide `.env.example` with placeholders only.

The developer should not need a paid account or cloud credentials to run the application.

## 15. Testing Requirements

Write meaningful automated tests for every critical module.

### Java unit and integration tests

- Token bucket calculations.
- Sliding-window counter behavior.
- Concurrent rate-limiter requests.
- Hash ring determinism.
- Hash distribution.
- Minimal movement after adding/removing nodes.
- Node health filtering.
- Ownership epoch validation.
- Seat-lock acquisition.
- Wrong-token release and extension.
- Hold expiry.
- Every booking-state transition.
- Illegal state transitions.
- Duplicate confirmation.
- Idempotency key reuse with a different payload.
- Payment callback duplication.
- Stale payment callback after hold expiry.
- Database uniqueness under concurrent confirmation.
- Waitlist cursor behavior for every mode.
- Presence heartbeat and stale-session eviction.

### Node.js tests

- Registration and login.
- JWT verification.
- Catalog validation and pagination.
- Payment simulation.
- Callback retries.
- Notification idempotency.
- Consumer recovery.
- Dead-letter behavior.

### Integration tests

Test the actual Redis and PostgreSQL behavior, not only mocks, for critical concurrency guarantees.

Use disposable local containers or a documented test setup.

Tests must be deterministic wherever possible. Avoid relying on arbitrary sleeps when synchronization primitives or controlled clocks can be used.

## 16. k6 Load Tests

Create the following scripts under `/loadtest`.

### Test A: Same-seat flash-sale contention

- Simulate 1,000 concurrent attempts to reserve the same seat.
- Include the hold and confirmation workflow where applicable.
- Verify the final database state.
- Assert exactly one confirmed booking for that seat.
- Assert zero duplicate confirmed bookings.
- Report successes, rejections, errors, latency, and throughput.

Be precise about whether the 1,000 virtual users start simultaneously, how long the test runs, and how authentication and rate limits are configured.

A test that merely produces one successful HTTP response is insufficient; verify the persisted booking state.

### Test B: Rate limiting

- Generate traffic above the configured rate.
- Demonstrate successful requests and HTTP 429 responses.
- Verify `Retry-After` and rate-limit headers.
- Report allowed and blocked counts.

### Test C: Ring rebalance under traffic

- Generate requests for multiple shows.
- Add or remove a logical booking node during traffic.
- Measure ownership movement.
- Verify requests are routed to current owners.
- Check for stale-owner writes and booking failures.
- Verify database correctness after rebalancing.

If running a true 1,000-user scenario is resource-intensive in the available environment, implement the scenario and document any reduced local run separately. Never represent an unexecuted test as passed.

Record exact test parameters and results.

## 17. Observability and Operational Quality

Provide lightweight, local observability without paid services.

At minimum, include:

- Structured logs.
- Request IDs.
- Booking IDs and payment IDs in relevant logs.
- Health endpoints.
- Rate-limit metrics.
- Ring ownership and rebalance metrics.
- Booking success/failure counters.
- Notification retry and dead-letter counters.
- Meaningful error codes.
- Clear handling of expected conflicts and timeouts.

Do not log passwords, JWTs, payment secrets, or unnecessary personal data.

Document the main bottlenecks and operational failure modes.

## 18. Implementation Phases

Execute these phases sequentially.

### Phase 0: Repository inspection and engineering plan

- Inspect the existing directory, Git status, files, and tooling.
- Preserve useful existing work.
- Identify the current baseline.
- Create a concise implementation plan.
- Document service boundaries, API contracts, database ownership, Redis key conventions, and critical invariants.
- Do not overwrite or delete existing work without justification.

Commit a baseline plan if appropriate.

### Phase 1: Foundation, catalog, authentication, and infrastructure

- Create the monorepo structure.
- Set up Docker Compose.
- Configure PostgreSQL schemas and migrations.
- Configure Redis.
- Implement authentication and catalog services.
- Seed users, venues, seats, and shows.
- Add health endpoints.
- Verify startup and basic API flows.

Commit message example:

`feat(infra): initialize local platform and catalog services`

### Phase 2: Booking engine and payment workflow

- Implement seat locking.
- Implement booking states.
- Implement durable idempotency.
- Implement confirmation transactions.
- Implement payment mock and callbacks.
- Implement compensation for late payment success.
- Add concurrency and lifecycle tests.
- Verify that concurrent users cannot confirm the same seat.

Commit message example:

`feat(booking): implement seat holds and idempotent confirmation`

### Phase 3: Gateway and rate limiting

- Implement proxy routing.
- Implement JWT validation.
- Implement token bucket.
- Implement sliding-window counter.
- Add atomic Redis Lua scripts.
- Add headers, counters, and tests.

Commit message example:

`feat(gateway): add proxy routing and custom rate limiters`

### Phase 4: Consistent hashing and safe rebalancing

- Implement the hash ring.
- Implement virtual nodes.
- Implement show ownership.
- Implement node health checks.
- Add and remove logical nodes.
- Introduce ownership epochs/fencing.
- Measure ownership movement.
- Test rebalancing under concurrent traffic.

Commit message example:

`feat(routing): implement consistent hashing and safe rebalance`

### Phase 5: Waitlist, presence, SSE, and domain events

- Implement cursor and offer-mode classes.
- Implement offer expiry and acceptance.
- Implement presence heartbeats.
- Implement live viewer counts.
- Implement SSE.
- Publish domain events.
- Test concurrency and recovery.

Commit message example:

`feat(realtime): add waitlist rotation and live show updates`

### Phase 6: Notification worker

- Implement Redis Streams consumer groups.
- Implement idempotent notifications.
- Implement retries, pending-message recovery, and dead-letter handling.
- Add operational counters and tests.

Commit message example:

`feat(notifications): add resilient stream consumer and DLQ`

### Phase 7: Frontend

- Implement authentication pages.
- Implement shows list.
- Implement seat map.
- Implement hold/pay flow.
- Implement bookings page.
- Implement waitlist UI.
- Implement admin dashboard.
- Connect everything to the backend.

Commit message example:

`feat(frontend): add booking experience and admin dashboard`

### Phase 8: Load tests and hardening

- Implement the three k6 scenarios.
- Run tests at available resource limits.
- Fix correctness and reliability failures.
- Verify persistence after restart.
- Verify container health and startup.
- Verify the complete local flow.

Commit message example:

`test(load): validate booking contention and routing behavior`

### Phase 9: Documentation and final verification

- Complete architecture documentation.
- Add Mermaid diagrams.
- Document APIs, migrations, and environment variables.
- Record test and benchmark results.
- Explain limitations and trade-offs.
- Write resume bullets supported by actual evidence.
- Verify the clean startup procedure.
- Confirm all expected tests pass.

Commit message example:

`docs(stagepass): document architecture and verified benchmarks`

Adapt commit messages to the actual work performed.

## 19. Required Documentation

Create a comprehensive README covering:

1. Project overview.
2. Features and service responsibilities.
3. Technology stack.
4. Repository structure.
5. Architecture diagram.
6. Booking sequence diagram.
7. Booking state diagram.
8. Waitlist offer-mode diagram.
9. Consistent-hash routing and rebalance explanation.
10. Seat-locking and idempotency guarantees.
11. Payment race conditions and compensation.
12. Redis key conventions.
13. PostgreSQL schema ownership.
14. API endpoints and example requests.
15. Environment variables.
16. How to start the complete application.
17. How to run tests.
18. How to run each k6 scenario.
19. Actual benchmark results and test environment.
20. Failure modes and recovery behavior.
21. Design decisions and trade-offs.
22. Known gaps and future improvements.
23. Resume bullet suggestions.

Use Mermaid for architecture, booking sequence, state transitions, and event-processing diagrams.

Keep the README focused on explaining engineering decisions, not just listing features.

## 20. Honest Architecture and Scale Claims

Explicitly document these simplifications:

- PostgreSQL and Redis are shared infrastructure rather than independently sharded clusters.
- The hash ring demonstrates routing and horizontal booking-node scaling; it does not prove that the complete application supports internet-scale traffic.
- PostgreSQL remains the durable source of truth for bookings and idempotency.
- Redis locks reduce contention, but database constraints and transactional validation protect final booking correctness.
- The payment processor is simulated.
- Email and SMS delivery are mocked.
- Wait-time estimates are approximate.
- The project is a portfolio demonstration, not a production-certified ticketing platform.

Document the behavior and limitations of node death, rebalance, lock expiry, duplicate callbacks, worker crashes, and event redelivery.

Do not claim exactly-once event delivery. Describe the actual delivery and idempotency guarantees.

Do not fabricate benchmark numbers, concurrency results, uptime, throughput, latency, or scalability claims.

## 21. Definition of Done

The project is complete when:

- `docker compose up --build` starts the entire platform.
- Seed data is available.
- A user can register and log in.
- A user can browse shows.
- Two browser sessions can compete for seats.
- Seat holds and countdowns work.
- Booking confirmation is durable and idempotent.
- Duplicate confirmed bookings are prevented.
- Payment failure, retry, and late callback behavior are handled.
- Booking state transitions are tested.
- Both rate-limit strategies work.
- Consistent hashing routes booking traffic.
- Logical node changes produce an ownership-movement report.
- Stale-owner writes are prevented.
- Waitlist offers rotate according to the selected strategy.
- Presence and SSE updates work.
- Redis Streams notifications are processed with retries and dead-letter handling.
- The admin dashboard displays actual backend data.
- The three k6 scenarios exist and produce verifiable results when run.
- Critical automated tests pass.
- The README explains the architecture and known gaps.
- Git history contains clear commits for completed phases.

If a requirement cannot be completed, document the blocker and remaining work explicitly instead of silently omitting the feature.

## 22. Working Protocol for Codex

Follow this protocol throughout the project:

1. Inspect the repository before changing anything.
2. Check the Git status and preserve unrelated modifications.
3. Establish a working baseline.
4. Implement one phase at a time.
5. Run relevant formatting, compilation, unit tests, integration tests, and startup checks.
6. Fix failures before declaring the phase complete.
7. Commit only the changes belonging to the completed phase.
8. Never commit broken or unverified work merely to meet a phase deadline.
9. Keep configuration and API contracts consistent across services.
10. Update documentation whenever architecture or behavior changes.
11. Do not leave placeholder implementations for critical features.
12. Do not silently replace custom algorithms with libraries.
13. If tooling, Docker, network access, or dependencies are unavailable, report the limitation and continue with work that can be verified.
14. Avoid unrelated refactoring and unnecessary dependencies.
15. Maintain a concise `docs/IMPLEMENTATION_STATUS.md` containing completed phases, test results, outstanding issues, and the next step.

After each phase, provide a concise report:

- What was implemented.
- Important files changed.
- Tests executed and their actual outcomes.
- Commands to run or reproduce the results.
- Known issues.
- Git commit hash.
- Next phase.

Do not stop after creating a plan or repository skeleton. Continue autonomously through the implementation phases unless genuinely blocked by the environment or an essential decision.

## 23. Start Now

Begin with Phase 0.

Inspect the currently opened repository, determine whether it is empty or contains existing code, check the available Java, Maven, Node.js, Docker, and Git tooling, and inspect the current Git status.

Then create or update `docs/IMPLEMENTATION_STATUS.md` and the initial engineering plan.

Proceed immediately into Phase 1 when the baseline is understood.

Do not ask me to repeat this specification. Make reasonable assumptions, document them, and start building the actual application.
