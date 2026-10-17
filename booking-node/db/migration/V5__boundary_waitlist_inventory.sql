INSERT INTO booking.sessions(id, show_id, user_id, state, hold_token, expires_at)
VALUES ('5dcb0147-06de-4e75-89be-912e9ed26f6a', 'show-boundary', 'seed-boundary-inventory', 'CONFIRMED',
        'de6d31f4-9d21-49b3-a220-64af8ab2c1a1', '2099-01-01T00:00:00Z')
ON CONFLICT (id) DO NOTHING;

INSERT INTO booking.transitions(booking_id, from_state, to_state, reason)
VALUES ('5dcb0147-06de-4e75-89be-912e9ed26f6a', 'PAYMENT_PENDING', 'CONFIRMED', 'seeded demo inventory')
ON CONFLICT DO NOTHING;

INSERT INTO booking.session_seats(booking_id, show_id, seat_id, price_cents)
SELECT '5dcb0147-06de-4e75-89be-912e9ed26f6a', 'show-boundary', seat.id, 5000
FROM catalog.seats seat
WHERE seat.section_id = 'sec-boundary-ga'
  AND seat.id NOT IN ('sec-boundary-ga-A-1', 'sec-boundary-ga-A-2', 'sec-boundary-ga-A-3')
ON CONFLICT (booking_id, seat_id) DO NOTHING;

INSERT INTO booking.confirmed_seats(show_id, seat_id, booking_id)
SELECT 'show-boundary', seat.id, '5dcb0147-06de-4e75-89be-912e9ed26f6a'
FROM catalog.seats seat
WHERE seat.section_id = 'sec-boundary-ga'
  AND seat.id NOT IN ('sec-boundary-ga-A-1', 'sec-boundary-ga-A-2', 'sec-boundary-ga-A-3')
ON CONFLICT (show_id, seat_id) DO NOTHING;
