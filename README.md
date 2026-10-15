# StagePass

StagePass is a local portfolio project for ticket sales under contention. PostgreSQL is the durable source of truth and Redis coordinates short-lived seat holds. Phases 0–2 provide infrastructure, authentication, catalog, a booking engine, and a mock payment service. A small React interface has been brought forward to make these backend flows easier to explore. Gateway routing, custom rate limiting, consistent hashing, waitlists, notifications, and the complete frontend remain future work.

## Current status

See [implementation status](docs/IMPLEMENTATION_STATUS.md) and [engineering plan](docs/ENGINEERING_PLAN.md). Phases 0–2 are implemented; the frontend MVP is in progress and pulled forward from Phase 7. Gateway routing/rate limiting, consistent hashing, waitlists/realtime, notifications, full frontend features, and load tests remain.

## Services in the current phase

| Service | Port | Owns | Current endpoints |
| --- | ---: | --- | --- |
| Auth service | 8081 | `auth` schema | `GET /health`, `POST /auth/register`, `POST /auth/login` |
| Catalog service | 8082 | `catalog` schema | `GET /health`, `GET /shows`, `GET /shows/:showId`, `GET /shows/:showId/seats` |
| Booking nodes (3) | 8091–8093 | `booking` schema | `GET /health`, `GET /shows/:showId/seats`, `POST /shows/:showId/holds`, `PUT /bookings/:id/extend`, `POST /bookings/:id/confirm`, `POST /bookings/:id/cancel`, `GET /bookings` |
| Payment service | 8090 | `payment` schema | `GET /health`; internal intent, callback status, and refund endpoints |
| Frontend | 5173 | Browser UI | React/Vite interface for shows, seats, holds, bookings, and mock checkout |
| PostgreSQL | 5432 | Durable service schemas | Health checked by Compose |
| Redis | 6379 | Future locks, rate limit state, presence, and events | AOF enabled |

Registration accepts `{ "email", "password", "displayName" }`. Login accepts `{ "email", "password" }` and returns a one-hour HS256 JWT. A local-only admin account is seeded as `demo@stagepass.local` / `stagepass-demo`; change or remove it before exposing the service outside a local machine. Catalog listing supports `limit`, `cursor`, and `status`. Show and seat responses use deterministic seed IDs such as `show-aurora`.

Booking calls require `Authorization: Bearer <accessToken>`. Seat availability reports `AVAILABLE`, `HELD`, or `CONFIRMED`. A successful hold reserves 1–8 venue seats for five minutes using token-owned Redis keys. Confirmation requires `Idempotency-Key` and a mock token: `pm_test_success` succeeds, `pm_test_decline` returns a payment failure and retains the hold so the buyer can retry with a new idempotency key, and `pm_test_late_success` simulates a delayed callback. Late success after cancellation or expiry is refunded. No real card data is accepted or stored.

## Start locally

Copy `.env.example` to `.env` and replace `JWT_SECRET` with a local secret of at least 32 characters. The defaults are for local development only.

```sh
docker compose up --build
```

Compose starts PostgreSQL and Redis, runs service-owned Flyway migrations, and starts auth, catalog, payment, and three booking-node instances. Seed shows and seats are inserted by the catalog migration. PostgreSQL and Redis data persist in named volumes.

Open [http://localhost:5173](http://localhost:5173) for the visual interface. It proxies browser API calls through its local Vite server to auth, catalog, and booking node 1, so the APIs do not need browser CORS configuration. The page shows service health, lists shows, displays live seat states, and lets you register/sign in, hold seats, extend/cancel bookings, and simulate success, decline, or delayed payment. It refreshes booking state to show asynchronous payment callbacks. Waitlists, SSE updates, admin dashboards, and gateway routing are not implemented yet.

For frontend-only development outside Docker, use `npm ci && npm run dev` inside `frontend/`; Vite proxies to `localhost:8081`, `localhost:8082`, and `localhost:8091` by default.

Example requests:

```sh
curl http://localhost:8082/shows
curl http://localhost:8082/shows/show-aurora/seats
curl -X POST http://localhost:8081/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.test","password":"local-demo-password","displayName":"Demo User"}'
```

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

## Data ownership

- `auth-service/db/migration` owns `auth.users`.
- `catalog-service/db/migration` owns `catalog.venues`, `catalog.shows`, `catalog.sections`, and `catalog.seats`.
- `booking-node/db/migration` owns booking sessions, selected seats, confirmed seats, transitions, and durable idempotency records.
- `booking-node/db/migration` also owns durable payment attempts used to deduplicate callbacks.
- `payment-service/db/migration` owns mock payment intents, refund state, and retryable callback delivery metadata.
- Notification schema is reserved for a later phase.

## Security and current limitations

- Passwords are hashed with bcrypt. JWT signing requires `JWT_SECRET`; no secret is committed.
- The booking node validates the same HS256 issuer and secret as auth; gateway-level JWT validation and public routing are later phases.
- Redis reduces hold contention, while `booking.confirmed_seats` has a durable `(show_id, seat_id)` primary key as the final duplicate-booking defense.
- Payment is simulated. Synchronous confirmation and delayed callback paths use idempotency keys; the payment worker retries callbacks from durable PostgreSQL state, and callback processing is safe to redeliver. Refund compensation covers late success after cancellation/expiry and a database seat-uniqueness race.
- The local payment service accepts only mock method tokens and must not be exposed as a real payment processor.
- The smoke test is a local integration check, not a high-load benchmark. Callback retry behavior across a payment-service process crash and very large database contention loads remain to be tested in later hardening work.
- The project is a local portfolio demonstration, not a production-certified ticketing platform. PostgreSQL and Redis are shared instances; payment and message delivery are intended to be simulated.
