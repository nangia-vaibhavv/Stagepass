# Phase 5 API contract

All endpoints are exposed through the gateway under `/api/booking`. Show-level routes are served by booking nodes. Authenticated operations use the existing bearer JWT.

| Method and path | Auth | Request | Success |
| --- | --- | --- | --- |
| `POST /shows/{showId}/presence` | Bearer JWT | Empty body; JWT identity is hashed server-side | `200 { "activeViewers": 12, "activeWindowSeconds": 30 }` |
| `GET /shows/{showId}/presence` | Public | — | Same count response |
| `GET /shows/{showId}/events` | Public, `Accept: text/event-stream` | — | SSE events; reconnect by refreshing show state if history is missed |
| `POST /shows/{showId}/waitlist` | Bearer JWT | `{ "quantity": 2 }` (1–8) | `201 { "showId", "quantity", "position", "estimatedWaitSeconds", "offeredSeatIds", "offerExpiresAtEpochMillis" }` |
| `GET /shows/{showId}/waitlist/me` | Bearer JWT | — | Caller queue position and any assigned seat offer |
| `POST /shows/{showId}/waitlist/accept` | Bearer JWT | — | Converts the caller's reserved multi-seat offer into a normal five-minute booking hold |
| `PUT /admin/shows/{showId}/waitlist-mode` | Admin JWT | `{ "mode": "OFFER_NEXT|RETRY_CURRENT|ROUND_ROBIN|RELEASE_TO_POOL" }` | `{ "showId", "mode" }` |

Presence uses one pseudonymous member per user and a 30-second active window; clients should heartbeat every 10 seconds. Repeated heartbeats update the same member. Waitlist order is a show-wide Redis sorted set. Each user requests 1–8 seats; when enough seats are available, the allocator assigns the next available seats in catalog order and reserves them in Redis for 60 seconds. Offers are private in the authenticated position endpoint; public SSE events include the assigned seat IDs but no personal identifiers. Accepting an offer atomically converts the reservation to the existing five-minute booking hold. If an offer expires, its seat reservations are released and the configured offer mode controls whether the user is skipped or requeued. The displayed wait estimate is a rough 60-second-per-person queue estimate, not a measured recent admission rate. Redis AOF is the local recovery mechanism for queue and presence data; PostgreSQL remains authoritative for bookings.

Booking domain events are appended to Redis Stream `stagepass:events` with fields `eventId`, `eventType`, `aggregateId`, `occurredAt`, `schemaVersion`, and JSON `data`. Delivery is at least once. An outbox row is inserted in the booking transaction and a scheduled relay publishes it, so a process crash after commit does not lose the event. SSE is a live notification channel, not a durable event log: clients refresh the seat snapshot after reconnect.

## Event types

- `seat.held`, `seat.booked`, `booking.confirmed`, `booking.cancelled`, `booking.expired`, `seat.released`
- `waitlist.offered`, `waitlist.offer_expired`, `presence.count_updated`

Personal identifiers are not included in public SSE events. Waitlist endpoints return only the authenticated caller's position and never disclose other users.
