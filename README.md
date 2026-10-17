# StagePass

StagePass is a local portfolio project for ticket sales under contention. PostgreSQL is the durable source of truth; Redis coordinates short-lived seat holds, distributed rate limits, presence, and waitlists. Phases 0–3 provide infrastructure, authentication, catalog, a booking engine, mock payments, a public API gateway, and a React interface. Phase 5 realtime and show-wide waitlist work is in progress; consistent-hash routing (Phase 4) is deferred.

## Current status

See the [next-assistant handoff](docs/HANDOFF_NEXT_ASSISTANT.md), [implementation status](docs/IMPLEMENTATION_STATUS.md), [engineering plan](docs/ENGINEERING_PLAN.md), and the [Phase 5 API contract](docs/PHASE5_API.md). Phases 0–3 are implemented; Phase 5 backend and frontend work is in progress. Phase 4 routing is deferred. Notifications and load tests remain.

## Services in the current phase

| Service | Port | Owns | Current endpoints |
| --- | ---: | --- | --- |
| Gateway | 8080 | Public API routing, JWT validation, and custom rate limiting | `GET /health`, `/api/auth/**`, `/api/catalog/**`, `/api/booking/**`, `GET /admin/rate-limits` |
| Auth service | 8081 | `auth` schema | `GET /health`, `POST /auth/register`, `POST /auth/login` |
| Catalog service | 8082 | `catalog` schema | `GET /health`, `GET /shows`, `GET /shows/:showId`, `GET /shows/:showId/seats` |
| Booking nodes (3) | 8091–8093 | `booking` schema | `GET /health`, `GET /shows/:showId/seats`, `POST /shows/:showId/holds`, `PUT /bookings/:id/extend`, `POST /bookings/:id/confirm`, `POST /bookings/:id/cancel`, `GET /bookings` |
| Payment service | 8090 | `payment` schema | `GET /health`; internal intent, callback status, and refund endpoints |
| Frontend | 5173 | Browser UI | React/Vite interface for shows, seats, holds, bookings, and mock checkout |
| PostgreSQL | 5432 | Durable service schemas | Health checked by Compose |
| Redis | 6379 | Booking holds, rate-limit state, presence, waitlists, and event stream | AOF enabled |

Registration accepts `{ "email", "password", "displayName" }`. Login accepts `{ "email", "password" }` and returns a one-hour HS256 JWT. A local-only admin account is seeded as `demo@stagepass.local` / `stagepass-demo`; change or remove it before exposing the service outside a local machine. Catalog listing supports `limit`, `cursor`, and `status`. Show and seat responses use deterministic seed IDs such as `show-aurora`.

Booking calls require `Authorization: Bearer <accessToken>`. Seat availability reports `AVAILABLE`, `HELD`, or `CONFIRMED`. A successful hold reserves 1–8 venue seats for five minutes using token-owned Redis keys. Confirmation requires `Idempotency-Key` and a mock token: `pm_test_success` succeeds, `pm_test_decline` returns a payment failure and retains the hold so the buyer can retry with a new idempotency key, and `pm_test_late_success` simulates a delayed callback. Late success after cancellation or expiry is refunded. No real card data is accepted or stored.

## Start locally

Copy `.env.example` to `.env` and replace `JWT_SECRET` with a local secret of at least 32 characters. The defaults are for local development only.

```sh
docker compose up --build
```

Compose starts PostgreSQL and Redis, runs service-owned Flyway migrations, and starts auth, catalog, payment, the gateway, and three booking-node instances. Seed shows and seats are inserted by the catalog migration. PostgreSQL and Redis data persist in named volumes.

Open [http://localhost:5173](http://localhost:5173) for the visual interface. It proxies browser API calls through Vite to the public gateway at port 8080. The page shows service health, lists shows, displays seat states, and lets you register/sign in, hold seats, extend/cancel bookings, and simulate success, decline, or delayed payment. If you try to hold seats while signed out, it prompts you to sign in or create an account. The selected show's “Audience & waitlist” panel shows the active viewer count, lets a signed-in user request 1–8 seats in the show-wide queue, and displays a live offer for whichever seats become available.

To test a two-seat offer, select **Boundary Waitlist Demo**, which starts with 57 confirmed seats and exactly three available. Use two separate browser profiles and create two different local accounts. In profile A, hold those three available seats. In profile B, request **2 seats** in the show's waitlist panel. Back in profile A, cancel the hold. Profile B should receive an offer for any two of the released seats; click **Accept offer**, then pay from **Bookings**. The offer reserves its seats for 60 seconds, and acceptance converts them to the normal five-minute booking hold. Keep both profiles open and signed in while testing; browser tabs in one profile share the same login session.

For frontend-only development outside Docker, use `npm ci && npm run dev` inside `frontend/`; Vite proxies API calls to `localhost:8080` by default.

Example requests:

```sh
curl http://localhost:8082/shows
curl http://localhost:8082/shows/show-aurora/seats
curl -X POST http://localhost:8081/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.test","password":"local-demo-password","displayName":"Demo User"}'
```

For public API examples, use the gateway prefixes. It rewrites login and registration to the auth service's `/auth/*` endpoints and strips `/api/catalog` or `/api/booking` before forwarding. For example:

```sh
curl http://localhost:8080/api/catalog/shows
curl http://localhost:8080/api/booking/shows/show-copper/seats
curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@stagepass.local","password":"stagepass-demo"}'
```

Protected booking endpoints require the same Bearer JWT as the booking node. The gateway verifies the HS256 signature and issuer before forwarding; the booking service verifies it again. Booking requests are temporarily distributed round-robin across all three nodes. Consistent-hash show ownership is a later phase.

## Gateway rate limits

The gateway uses Lua scripts in Redis for atomic distributed updates; no rate-limit library is used. YAML policies are in `gateway/src/main/resources/application.yml`:

- Login and registration share a weighted sliding-window counter: 10 requests per 60 seconds per source IP.
- Seat holds use a token bucket per authenticated user: burst of 5, replenished at 5 tokens per minute.
- Confirmations use a separate token bucket per authenticated user with the same capacity/refill.
- Other API calls use a weighted sliding-window counter: 120 requests per 60 seconds per authenticated user or source IP.

Allowed API responses include `X-RateLimit-Rule`, `X-RateLimit-Limit`, `X-RateLimit-Remaining`, and `X-RateLimit-Reset`. Blocked requests return HTTP 429 and `Retry-After`. Login identity uses the gateway socket's remote address; client-supplied `X-Forwarded-For` and similar headers are ignored. The sliding-window counter weights the previous fixed window by the fraction of time remaining, so it approximates a true rolling window. `GET /admin/rate-limits` reports aggregate allowed/blocked counts and requires an admin JWT.

To exercise booking with the seeded local user, log in and copy the returned `accessToken` into `TOKEN` in your shell:

```sh
TOKEN='<accessToken from login>'
curl -X POST http://localhost:8091/shows/show-copper/holds \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"seatIds":["sec-foundry-ga-A-1"]}'
```

Copy the returned `bookingId`, then confirm it with a fresh idempotency key:

```sh
BOOKING_ID='paste-booking-id-here'
curl -X POST "http://localhost:8091/bookings/$BOOKING_ID/confirm" \
  -H "Authorization: Bearer $TOKEN" -H 'Idempotency-Key: demo-confirm-001' \
  -H 'Content-Type: application/json' \
  -d '{"paymentMethodToken":"pm_test_success"}'
```

The same confirmation key and body replay the stored response. Reusing that key with a changed payment token returns `IDEMPOTENCY_KEY_REUSED`. The three booking ports share PostgreSQL/Redis state, so retry the same booking request on another port to observe the same durable state.

## Phase 2 tests

With Docker Desktop running and the Compose stack started, run:

```sh
npm test --prefix payment-service
node scripts/phase2-smoke.mjs
```

The smoke test races two authenticated users for one seat, exercises hold extension, a declined payment and retry, cross-node idempotency, a booking-node restart with an active hold, then cancels a pending payment and verifies the delayed success was refunded and acknowledged. It creates a random test account and confirms two seats in the local database each run; use seeded local data only. The Java state-machine suite runs during `docker compose build booking-node-1` and uses JUnit 5.

## Phase 5 API preview

Once the stack is rebuilt, the new endpoints support presence heartbeats, show-wide multi-seat waitlist requests, assigned seat offers, admin offer-mode selection, and SSE. Full request/response details are in [docs/PHASE5_API.md](docs/PHASE5_API.md). The local smoke script is:

```sh
docker compose up --build -d
node scripts/phase5-smoke.mjs
```

Presence should be sent every 10 seconds and counts only viewers active in the last 30 seconds. Waitlist offers expire after 60 seconds; acceptance still goes through the normal seat-hold lock. SSE is a best-effort live channel: reconnecting clients should refresh the seat snapshot. Booking domain events are written to a PostgreSQL outbox and relayed at least once to Redis Stream `stagepass:events`.

## Data ownership

- `auth-service/db/migration` owns `auth.users`.
- `catalog-service/db/migration` owns `catalog.venues`, `catalog.shows`, `catalog.sections`, and `catalog.seats`.
- `booking-node/db/migration` owns booking sessions, selected seats, confirmed seats, transitions, and durable idempotency records.
- `booking-node/db/migration` also owns durable payment attempts used to deduplicate callbacks.
- `booking-node/db/migration` owns the transactional event outbox and the confirmed-seat fixture for the Boundary Waitlist Demo.
- `payment-service/db/migration` owns mock payment intents, refund state, and retryable callback delivery metadata.
- Notification schema is reserved for a later phase.

## Security and current limitations

- Passwords are hashed with bcrypt. JWT signing requires `JWT_SECRET`; no secret is committed.
- The gateway and booking nodes validate the same HS256 issuer and secret as auth; booking JWTs are checked at both layers.
- Redis reduces hold contention, while `booking.confirmed_seats` has a durable `(show_id, seat_id)` primary key as the final duplicate-booking defense.
- Payment is simulated. Synchronous confirmation and delayed callback paths use idempotency keys; the payment worker retries callbacks from durable PostgreSQL state, and callback processing is safe to redeliver. Refund compensation covers late success after cancellation/expiry and a database seat-uniqueness race.
- The local payment service accepts only mock method tokens and must not be exposed as a real payment processor.
- The smoke test is a local integration check, not a high-load benchmark. Callback retry behavior across a payment-service process crash and very large database contention loads remain to be tested in later hardening work.
- The project is a local portfolio demonstration, not a production-certified ticketing platform. PostgreSQL and Redis are shared instances; payment and message delivery are intended to be simulated.
