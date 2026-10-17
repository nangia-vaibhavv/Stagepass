# StagePass implementation status

## Baseline

- Repository was empty when work began; there was no Git history or existing code to preserve.
- Git 2.56.0, Node.js 26/npm 11, and Docker Compose 5.6.0 are available on the current host; Java and Maven are not installed on the host.
- Java 21/Maven builds run inside the pinned `booking-node` build image.
- A local `.env` may exist, is ignored by Git, and must stay uncommitted.

## Phases

| Phase | Status | Evidence |
| --- | --- | --- |
| 0. Repository inspection and plan | Complete | Empty baseline inspected; plan committed as `9561b5f`. |
| 1. Foundation, catalog, authentication, infrastructure | Complete | Compose, PostgreSQL/Redis, Flyway migrations, seeded catalog, auth/catalog APIs created. User manually confirmed Compose startup, catalog endpoints, and demo login worked. |
| 2. Booking engine and payment workflow | Complete | Three Java booking nodes, Redis seat holds, booking state pattern, durable idempotency, Node mock payments, payment callback retry/refund, and integration smoke test. |
| 3. Gateway and rate limiting | Complete | Java gateway proxies auth/catalog/booking APIs; validates JWTs, applies custom Redis Lua token-bucket and weighted sliding-window policies, emits rate-limit headers/429 responses, and exposes admin aggregate counters. |
| 4. Consistent hashing and safe rebalancing | Deferred / removed from current scope | Local Phase 4 code was removed at the user's request; Phase 3 round-robin gateway routing is restored. The applied Flyway V3 migration file is retained so the existing local database can still start. |
| 5. Waitlist, presence, SSE, and domain events | In progress | Redis presence and show-wide multi-seat waitlist, seat reservations/offers, booking outbox/Redis Stream relay, SSE subscribers, gateway streaming route, React controls, Boundary Waitlist Demo fixture, and smoke script are implemented; broader recovery and concurrency checks remain. |
| 6. Notification worker | Not started | |
| 7. Frontend | In progress (MVP preview pulled forward) | React/Vite UI for show browsing, seat selection, booking lifecycle, and mock checkout; connected through a local API proxy. |
| 8. Load tests and hardening | Not started | |
| 9. Documentation and final verification | Not started | |

## Public deployment status

- User wants a shareable public URL without AWS costs, live PostgreSQL inspection, and a path to deploy future feature updates.
- Public frontend: [https://stagepass-zeta.vercel.app](https://stagepass-zeta.vercel.app). The page renders, but API requests still return `UPSTREAM_UNAVAILABLE`; the deployment is not yet complete.
- Vercel project `stagepass` uses the Services framework and Hobby plan. Neon `stagepass-prod` (Free, Singapore) and Upstash Redis (Free) are connected. `JWT_SECRET` and `PAYMENT_SERVICE_TOKEN` are set in Production. Never reveal or copy their values.
- Neon migrations and demo seeds have been applied. `booking.outbox_events` exists; Boundary Hall has 60 seats, 57 confirmed and 3 available. The demo admin was removed. Read-only mode was restored after migration.
- Runtime diagnosis found Java rejecting Vercel's internal `*.services.vercel-infra.com` TLS chain (`SunCertPathBuilderException`). Current local changes replace Java-to-binding calls with same-deployment HTTPS routes guarded by an internal token. The fix is not live until Vercel builds the new commit and we verify it.
- A Java build could not run locally because Maven is absent, and Docker could not access its local socket in this session. Vercel's container build is the compile check. Node syntax and root `vercel.json` parsing passed; `git diff --check` passed.
- The last verified production deployment (`fe31d31`) included sanitized TLS diagnostics but still returned `UPSTREAM_UNAVAILABLE`. Verify health, shows, registration/login, holds, mock payment outcomes, callbacks, waitlisting, SSE, and database inspection before calling it complete.
- The user explicitly asked to defer rotating the Neon password while the app is made functional. A prior Vercel log entry exposed the database URL; do not repeat or copy its value. Keep rotation as a pending follow-up.
- OCI Compose remains a fallback; no OCI VM was created. This is a portfolio demo only, not real ticket sales. The user authorized pushing and hosting the deployment changes; keep secrets out of Git and chat.

## Phase 1 verification

- Both JavaScript service files parsed with Node's module parser, both package manifests parsed as JSON, and `git diff --check` was clean.
- The user manually ran `docker compose up --build`, checked the Compose services, and verified `GET /shows`, `GET /shows/show-aurora/seats`, and `POST /auth/login` successfully.
- This is a manual smoke check, not an automated test suite or a full-platform verification. The gateway, booking, payment, notification, and frontend services belong to later phases.
- The environment initially lacked the application toolchain; after Docker Desktop was installed and started, the user manually verified the Phase 1 flow.
- Node services use exact direct dependency pins and checked-in npm lockfiles.

## Phase 2 verification

- `docker compose config -q`: passed.
- `npm test --prefix payment-service`: 2 tests passed.
- `docker compose build booking-node-1`: Maven package succeeded; 5 JUnit state tests passed.
- `docker compose up --build -d`: all services and booking/payment migrations started successfully.
- `node scripts/phase2-smoke.mjs`: passed seat contention (one hold/one conflict), hold extension, payment decline/retry, idempotency replay across nodes, payload mismatch rejection, active hold recovery after booking-node restart, and delayed-success refund after cancellation.
- The smoke test creates a random test user and confirms two seeded seats per run; these rows remain in the local database.

## Frontend preview verification

- `docker compose config -q`: passed with the frontend service added.
- `npm ci` and `npm run build` in `frontend/`: passed.
- `npm audit --audit-level=high` in `frontend/`: passed with zero reported vulnerabilities after pinning Vite 6.4.4.
- `docker compose up --build -d frontend`: frontend container started and reported healthy at port 5173.
- Requests from inside the frontend container through `/api/auth/health`, `/api/catalog/health`, `/api/booking/health`, and `/api/catalog/shows` returned HTTP 200.
- Opened the UI in the browser and verified seeded shows, booking-node seat states, and seat selection render. The complete sign-in-to-confirm flow still needs a user walkthrough in the UI.

## Phase 3 verification

- `docker compose config -q`: passed.
- `docker compose build gateway`: passed; 4 JUnit tests passed.
- `docker compose up --build -d`: all services started; auth, catalog, Redis, booking nodes, payment, gateway, and frontend reported healthy.
- Gateway API proxy checks: auth/catalog/booking health and seeded show listing returned HTTP 200; authenticated login and booking list returned HTTP 200; anonymous booking list returned HTTP 401; admin metrics returned HTTP 200 for the seeded admin.
- Three consecutive booking health requests through the gateway reached booking nodes 2, 3, and 1, confirming temporary round-robin selection.
- Eleven invalid login attempts from the gateway container returned ten HTTP 401 responses followed by HTTP 429 with `Retry-After` and `X-RateLimit-*` headers.
- Six confirmation requests using a valid but ephemeral JWT and malformed booking UUID returned five HTTP 400 responses followed by HTTP 429. This exercised the token-bucket policy without writing a user or booking row.
- Admin metrics showed allowed/blocked counts for login and confirmation rules.
- Consistent-hash routing, health-based node selection, and ownership fencing remain deferred with Phase 4. The Phase 5 SSE route is now implemented and verified.

## Phase 5 verification

- `docker compose config -q`, `node --check scripts/phase5-smoke.mjs`, and `git diff --check`: passed.
- `docker compose build booking-node-1 gateway`: passed; booking-node has 9 passing JUnit tests (5 lifecycle, 4 offer-mode tests), gateway has 4 passing tests.
- `docker compose up --build -d`: passed; the V4 outbox migration completed and booking nodes/gateway became healthy.
- `node scripts/phase5-smoke.mjs`: passed presence heartbeat/count, show-wide quantity-two waitlist, admin `ROUND_ROBIN` mode update, hold cancellation triggering a two-seat reservation offer, acceptance creating a normal hold, and `waitlist.offered` arriving over gateway SSE.
- Inventory check for `show-boundary`: 60 seats total, 57 confirmed, exactly 3 available, and 0 held after smoke cleanup. Smoke accounts and booking history remain in local PostgreSQL.

## Next step

Phase 5 core backend and React demo flows are implemented and the two-seat offer was smoke-tested locally. Remaining work includes broader offer-expiry/recovery/concurrency checks and additional frontend refinements. Phase 4 is out of scope unless requested again. Next is Phase 6 notifications.
