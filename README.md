# StagePass

StagePass is a local portfolio project for ticket sales under contention. The design keeps PostgreSQL as the durable source of truth and uses Redis for short-lived coordination. The first implementation phase provides the local database/cache foundation plus authentication and catalog APIs. Booking, gateway routing, payment, notifications, and the UI are planned phases and are not implemented yet.

## Current status

See [implementation status](docs/IMPLEMENTATION_STATUS.md) and [engineering plan](docs/ENGINEERING_PLAN.md). The execution environment has Git but no Java, Maven, Node.js, npm, or Docker, so container startup and runtime tests have not been verified.

## Services in the current phase

| Service | Port | Owns | Current endpoints |
| --- | ---: | --- | --- |
| Auth service | 8081 | `auth` schema | `GET /health`, `POST /auth/register`, `POST /auth/login` |
| Catalog service | 8082 | `catalog` schema | `GET /health`, `GET /shows`, `GET /shows/:showId`, `GET /shows/:showId/seats` |
| PostgreSQL | 5432 | Durable service schemas | Health checked by Compose |
| Redis | 6379 | Future locks, rate limit state, presence, and events | AOF enabled |

Registration accepts `{ "email", "password", "displayName" }`. Login accepts `{ "email", "password" }` and returns a one-hour HS256 JWT. A local-only admin account is seeded as `demo@stagepass.local` / `stagepass-demo`; change or remove it before exposing the service outside a local machine. Catalog listing supports `limit`, `cursor`, and `status`. Show and seat responses use deterministic seed IDs such as `show-aurora`.

## Start locally

Copy `.env.example` to `.env` and replace `JWT_SECRET` with a local secret of at least 32 characters. The defaults are for local development only.

```sh
docker compose up --build
```

Compose starts PostgreSQL and Redis, runs separate Flyway migrations for the `auth` and `catalog` schemas, and then starts both services. Seed shows and seats are inserted by the catalog migration. PostgreSQL and Redis data persist in named volumes.

Example requests:

```sh
curl http://localhost:8082/shows
curl http://localhost:8082/shows/show-aurora/seats
curl -X POST http://localhost:8081/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"demo@example.test","password":"local-demo-password","displayName":"Demo User"}'
```

## Data ownership

- `auth-service/db/migration` owns `auth.users`.
- `catalog-service/db/migration` owns `catalog.venues`, `catalog.shows`, `catalog.sections`, and `catalog.seats`.
- Schemas for booking, payment, and notifications are reserved in the initial PostgreSQL bootstrap; their migrations will be added with those services.

## Security and current limitations

- Passwords are hashed with bcrypt. JWT signing requires `JWT_SECRET`; no secret is committed.
- Authentication currently issues tokens; JWT validation belongs in the gateway phase.
- Catalog seat layout is venue inventory. Availability and holds will be served by the booking service in a later phase.
- This phase has not been built or run because this environment lacks the required runtime and container tools. No test or startup result is claimed.
- The project is a local portfolio demonstration, not a production-certified ticketing platform. PostgreSQL and Redis are shared instances; payment and message delivery are intended to be simulated.
