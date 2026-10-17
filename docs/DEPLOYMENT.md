# Public demo deployment

## Goal and hosting choice

The app is a multi-container Compose stack (React, gateway, three Spring booking nodes, three Node services, PostgreSQL, and Redis). Vercel supports containerized HTTP services, and Vercel Services can combine multiple applications in one deployment. Both Services and Daemons are currently Beta features on all plans, including Hobby. Vercel containers are not a replacement for persistent PostgreSQL/Redis: external managed services are required, and each provider's free limits and dormancy rules need review. Vercel Hobby has finite function and daemon quotas, so a $0 demo depends on staying within them and the database providers' free limits. The existing Docker Compose stack itself is not deployed unchanged as one Vercel workload. This setup is for a portfolio demo, not production ticket sales.

References: [Vercel container deployments](https://vercel.com/kb/docker), [Vercel Services](https://vercel.com/kb/guide/vercel-services), [Vercel Daemons](https://vercel.com/docs/daemons), [Daemon limits](https://vercel.com/docs/daemons/limits), [Vercel Cron limits](https://vercel.com/docs/cron-jobs/usage-and-pricing), [Vercel Function usage and limits](https://vercel.com/docs/functions/usage-and-pricing), [Vercel Marketplace storage](https://vercel.com/docs/marketplace-storage), [OCI Always Free resources](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm).

The app is deployed on Vercel at [stagepass-zeta.vercel.app](https://stagepass-zeta.vercel.app/) using the Hobby plan, Neon PostgreSQL Free, and Upstash Redis Free. It is a portfolio demo, not a production ticketing service. Oracle Cloud remains a fallback if Vercel's Beta services flow or free usage limits make the full stack impractical. No OCI VM was created.

## Vercel deployment investigation and plan

The repo-root `vercel.json` defines the React UI and five HTTP services, rewrites browser API traffic through the gateway, and declares two Daemons for payment callbacks and booking background jobs. Vercel Services and Daemons are Beta on all plans, including Hobby. The dashboard project uses the Services framework. The existing `frontend/vercel.json` remains the standalone frontend-only configuration; a full deployment must be started from the repository root using the root `vercel.json`.

The existing app is not a set of stateless HTTP handlers only. `payment-service` polls for due callbacks every 500 ms. The Spring booking nodes run scheduled hold-expiry, outbox relay, and waitlist-offer expiry work; they also hold short-lived SSE connections. Those recurring tasks cannot safely depend on Vercel HTTP Function invocations. A Vercel Daemon may host a dedicated worker, but Hobby gets 10 Active CPU-hours per month across the team; Vercel stops Hobby daemons after this quota is exhausted. A daemon cannot receive inbound traffic, cannot be targeted by Service bindings/rewrites, and does not run in preview deployments. Services and Daemons are different deployment primitives; workers must reach their dependencies over supported networking. The code likely needs changes to separate workers cleanly and avoid starting duplicate schedulers in every request container. The docs say idle/waiting time does not count toward Active CPU, so real usage depends on the workload and must be measured rather than estimated from uptime.

Current Vercel architecture in `vercel.json`:

| Vercel service | Responsibility | Private dependencies |
| --- | --- | --- |
| `web` | React/Vite static frontend | Gateway via same-origin `/api` rewrite |
| `gateway` | Public API, auth/JWT checks, rate limiting | Auth, catalog, and booking internal routes on the same deployment HTTPS origin; Redis |
| `auth` | Registration and login | PostgreSQL |
| `catalog` | Shows, seats, venues | PostgreSQL |
| `payment` | Mock payment HTTP API | PostgreSQL |
| `booking` | Holds, bookings, waitlist, SSE | PostgreSQL, Redis, authenticated payment route |
| `payment-callbacks` daemon | Delayed mock payment callback retries | PostgreSQL; calls authenticated `/internal/payment-callback` route |
| `booking-jobs` daemon | Hold expiry, outbox relay, waitlist-offer expiry | PostgreSQL and Redis |

Three booking replicas are replaced with one Vercel booking service; its shared PostgreSQL/Redis state remains the concurrency source of truth. The HTTP booking service disables scheduled tasks, and the daemon starts the same Spring app in non-web mode with scheduling enabled. The Node payment HTTP service disables its callback timer, and a dedicated daemon runs that worker. Hobby Daemons quota is 10 Active CPU-hours/month across the team; waiting time does not count, but real consumption must be watched in Vercel Usage. Daemons run only in production and cannot receive inbound HTTP. Their callback is sent to the booking service via an authenticated public route; the token must remain private.

The original Vercel private service URLs use an internal TLS certificate that Java's default trust store rejected (`SunCertPathBuilderException`). The gateway and booking service are being changed to call token-checked internal paths on the deployment's normal HTTPS domain instead. `JWT_SECRET` is the internal gateway token for auth, catalog, and booking routes; payment retains `PAYMENT_SERVICE_TOKEN`. The public UI loads, but API requests currently return `UPSTREAM_UNAVAILABLE` until the updated deployment is Ready and verified.

Neon PostgreSQL is provisioned through the Vercel Marketplace as `stagepass-prod` in Singapore (`sin1`) on the Free plan, with Neon Auth and Realtime disabled. Upstash Redis is also connected on the Free plan. The app needs native Redis TCP/TLS (`rediss://`) because it uses Streams, Pub/Sub, Lua scripts, and Redis templates. Do not select a paid plan, enter a card for an upgrade, or accept a paid trial. Recheck current pricing and terms before changing providers.

Required production environment variables (set in the Vercel project dashboard, never in Git):

| Variable | Used by | Value |
| --- | --- | --- |
| `DATABASE_URL` | Node auth/catalog/payment services and payment daemon | Neon pooled PostgreSQL URL (`postgresql://...?...sslmode=require`) |
| `DATABASE_JDBC_URL` | Booking HTTP service and booking daemon | Same Neon pooled URL with a `jdbc:` prefix (`jdbc:postgresql://...?...sslmode=require`); the container can also derive this from `DATABASE_URL` |
| `DATABASE_USER` / `DATABASE_PASSWORD` | Booking Spring runtime and migration command | Neon database credentials; `PGUSER` and `PGPASSWORD` are accepted as fallbacks |
| `SPRING_DATA_REDIS_URL` | Gateway and booking service/worker | Upstash native TLS TCP URL beginning `rediss://` |
| `JWT_SECRET` | Auth, gateway, booking | New random secret of at least 32 characters |
| `PAYMENT_SERVICE_TOKEN` | Booking and payment service/worker | New random secret of at least 24 characters |
| `BOOKING_CALLBACK_URL` | Booking service | `https://<production-domain>/internal/payment-callback` |
| `STAGEPASS_SCHEDULING_ENABLED` | Booking HTTP service | `false`; booking Daemon overrides it with `--stagepass.scheduling.enabled=true` |
| `RUN_CALLBACK_WORKER` | Payment HTTP service | `false`; callback Daemon runs `src/callback-worker.js` directly |
| `TRUST_PROXY_HEADERS` | Gateway | `true` only because Vercel is the trusted ingress proxy |
| `CORS_ALLOWED_ORIGINS` | Gateway | Exact production origin; the UI normally uses same-origin API routing |

The Neon schemas and migrations have been applied to the live `stagepass-prod` database. The verified query found `booking.outbox_events`, `catalog.shows`, 60 Boundary Hall seats, and 57 confirmed seats, leaving the requested 3 available. The production demo admin was removed. Read-only mode was restored after the migration. Keep secrets in Vercel and in a private local shell only.

The gateway remains the public API entrypoint. The existing `frontend/vercel.json` is only the standalone frontend setup and does not deploy the backend. Do not call deployment complete until health routes and an end-to-end browser booking succeed.

With frontend and API under the same Vercel deployment origin, configure same-origin API routing so browser CORS is not needed. If the backend is instead deployed on OCI, set `CORS_ALLOWED_ORIGINS` to the exact frontend origin; do not allow every `*.vercel.app` origin for a long-lived public demo.

The user asked to defer rotating the Neon password while the app is made functional. A prior Vercel log entry exposed the database URL; do not repeat or copy its value. Keep rotation as a pending account-security follow-up for the user.

## OCI fallback configuration

The following OCI-specific configuration is prepared as a fallback. It is not the current preferred deployment path; do not provision the VM unless the Vercel container/services path is unavailable or does not fit the free-usage goal.

### Security choices for OCI

- The production React bundle is served by Nginx; Vite's development server is not exposed.
- Caddy is the only public application entry point. Open inbound TCP ports 80 and 443. Keep SSH (22) limited to your own current IP where possible.
- Production enables trusted forwarded-client-IP handling only in the private gateway, which is reachable publicly only through Caddy. Caddy overwrites untrusted incoming forwarding headers with the actual connecting client IP. Local Compose keeps proxy-header trust disabled.
- PostgreSQL binds only to the VM's loopback interface so it can be inspected through an SSH tunnel. Redis, gateway, auth, catalog, payment, and booking nodes have no published host ports.
- Production sets `DISABLE_DEMO_ADMIN=true`. The new auth migration removes the seeded `demo@stagepass.local` administrator on a fresh production database. Public visitors must register their own accounts. The frontend no longer displays or prefills the demo password.
- Set unique secrets in `deploy/.env.production`; never commit that file or share its values. Payments remain mock-only; do not collect real card data or use the demo for real ticket sales.
- Compose volumes persist data through container rebuilds. Never run `docker compose down -v` on the live instance.

### OCI setup checklist

1. Create/sign in to an OCI Free Tier account and check the home region for an Always Free ARM instance. Choose **VM.Standard.A1.Flex**, **2 OCPUs**, **12 GB memory**, Ubuntu ARM image, and a boot volume marked Always Free eligible. OCI currently documents the Always Free A1 allowance as 2 OCPUs and 12 GB, and host capacity can be unavailable. Review the estimated cost and Always Free labels before creating resources; do not select paid resources or upgrade the account.
2. Assign the VM a stable public IPv4 address if the console offers an Always Free option for the account. If the free shape is unavailable, stop and tell the user; do not switch to a paid shape.
3. In the OCI VCN/security rules and the VM firewall, allow inbound TCP 80 and 443 from the internet. Allow TCP 22 only from your IP if practical. Remove public ingress for 5432, 6379, 8080, 8081, 8082, 8090–8093, and 5173. OCI enforces VCN security rules and the VM also has an OS firewall; both must permit the web ports. See [OCI security rules](https://docs.oracle.com/en-us/iaas/Content/Network/Concepts/securityrules.htm).
4. Create a free name such as `my-stagepass.duckdns.org` at [DuckDNS](https://www.duckdns.org/) and point it to the VM's public IPv4. Wait for DNS to resolve before starting Caddy; Caddy obtains and renews the TLS certificate automatically after DNS and ports 80/443 work.
5. Install Docker Engine and the Docker Compose plugin on the VM using the official instructions for its OS and ARM architecture. Verify `docker compose version`.
6. Clone this repository to the VM. If it is private, use a read-only GitHub deploy key; don't put a GitHub token in the clone URL or a committed file.
7. In the repo on the VM, copy `deploy/.env.production.example` to `deploy/.env.production`. Set `STAGEPASS_DOMAIN` to the API DuckDNS/custom hostname and `CORS_ALLOWED_ORIGINS` to the exact Vercel frontend origin (`https://<project>.vercel.app`). Generate separate random secrets, for example:

   ```sh
   openssl rand -hex 32
   ```

   Use a fresh value for `POSTGRES_PASSWORD`, `JWT_SECRET`, and `PAYMENT_SERVICE_TOKEN`. Update both `POSTGRES_PASSWORD` and the matching password embedded in `POSTGRES_URL`. Keep `DISABLE_DEMO_ADMIN=true`.
8. Validate the merged Compose configuration and build images one at a time to reduce peak memory on the small VM, then start the stack:

   ```sh
   docker compose --env-file deploy/.env.production \
     -f docker-compose.yml -f deploy/docker-compose.production.yml config -q

   docker compose --parallel 1 --env-file deploy/.env.production \
     -f docker-compose.yml -f deploy/docker-compose.production.yml build

   docker compose --env-file deploy/.env.production \
     -f docker-compose.yml -f deploy/docker-compose.production.yml up -d

   docker compose --env-file deploy/.env.production \
     -f docker-compose.yml -f deploy/docker-compose.production.yml ps
   ```

9. Visit `https://<your-hostname>`. Register a fresh user and run a small hold/cancel/mock-payment walkthrough. Check service logs with `docker compose ... logs -f gateway booking-node-1 caddy` if something fails.
10. Make a database backup before feature deployments or schema changes:

    ```sh
    mkdir -p backups
    docker compose --env-file deploy/.env.production \
      -f docker-compose.yml -f deploy/docker-compose.production.yml \
      exec -T postgres pg_dump -U stagepass stagepass > "backups/stagepass-$(date +%F-%H%M).sql"
    ```

    Copy backups off the VM. A Docker volume is persistence across container replacement, not a backup against VM loss.

## Inspecting live PostgreSQL data

PostgreSQL runs in the `postgres` container and persists in the `postgres_data` volume on the VM. Use DBeaver Community (or another PostgreSQL client) with an SSH tunnel; do not open port 5432 to the internet.

With the database container running, create an SSH local forward from your laptop:

```sh
ssh -N -L 15432:127.0.0.1:5432 <vm-user>@<vm-public-ip>
```

Keep that terminal open. In DBeaver, create a PostgreSQL connection to `127.0.0.1:15432`, database `stagepass`, user `stagepass`, and the `POSTGRES_PASSWORD` from the server's private `deploy/.env.production`. If you prefer DBeaver's built-in SSH tunnel, configure SSH host `<vm-public-ip>`, port `22`, VM username/key, then forward remote `127.0.0.1:5432` to local port `15432`.

Expand **Schemas** to inspect:

- `auth.users` — account emails, display names, roles, password hashes (never copy hashes into chat or resume).
- `catalog.shows`, `catalog.venues`, `catalog.sections`, `catalog.seats` — seeded show and seat inventory.
- `booking.sessions`, `booking.session_seats`, `booking.confirmed_seats`, `booking.transitions`, `booking.idempotency_records`, `booking.payment_attempts`, `booking.outbox_events` — holds, confirmed seats, lifecycle, callbacks, and events.
- `payment.intents` — mock payment attempts/outcomes.

Example read-only query:

```sql
SELECT show_id, count(*) AS confirmed_seats
FROM booking.confirmed_seats
GROUP BY show_id
ORDER BY show_id;
```

Schema/table names can change as migrations evolve; inspect the current migrations under each service's `db/migration` directory.

## Updating the deployed app when adding features

Until CI/CD is deliberately added, deploy changes manually:

1. Develop and verify locally, then push the reviewed commit to the GitHub branch used by the VM.
2. SSH to the VM, `cd` to the cloned repository, and run `git pull --ff-only`.
3. Back up PostgreSQL (especially before a migration).
4. Run the `up -d --build` command above. Compose rebuilds changed images; Flyway applies new migrations before dependent services start.
5. Check `ps`, health, logs, and the public UI. Keep `.env.production` and backup files out of Git.

Next deployment enhancement: add GitHub Actions to run frontend build, Node tests, Java tests, and Compose config checks on every push; deploy over SSH only after those checks pass. Do not configure automatic live deployment until the user has created an SSH key/secret and reviewed that workflow.

## Resume material

Use only bullets that are true for the version you have verified. Do not claim production scale, real payment processing, zero downtime, or load-test throughput until those are actually demonstrated.

- Built **StagePass**, a React and Spring Boot ticket-booking demo with a Java API gateway, three booking-node replicas, Node.js services, PostgreSQL, Redis, and Docker Compose.
- Implemented Redis atomic seat holds and token-checked release/extension, with PostgreSQL uniqueness and transactional state transitions as the final protection against duplicate confirmed seats.
- Designed idempotent mock-payment workflows with delayed callback handling and compensation for late success after a hold expires or is cancelled.
- Implemented distributed token-bucket and weighted sliding-window rate limiters using Redis Lua scripts, plus JWT validation and observable 429/rate-limit headers.
- Added show-wide, multi-seat waitlisting, short-lived seat offers, active-viewer presence, PostgreSQL outbox events, Redis Streams/Pub/Sub, and SSE updates.
- Verified booking lifecycle and payment-race scenarios with automated smoke tests and JUnit tests; list exact passing tests and counts from the current implementation status, not unexecuted load scenarios.

Short version for a compact resume:

> Built a distributed ticket-booking portfolio app with React, Spring Boot, Node.js, PostgreSQL, Redis, and Docker Compose; implemented concurrency-safe seat holds, idempotent payment callbacks, distributed rate limiting, and live waitlist offers. Verified core booking and payment race paths with automated tests.

Tailor the wording to the job and keep the final sentence only if the current test evidence still passes. Phase 4 consistent-hash routing was explicitly removed from scope; do not claim it.
