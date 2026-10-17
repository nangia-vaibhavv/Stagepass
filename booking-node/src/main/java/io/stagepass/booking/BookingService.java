package io.stagepass.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

@Service
public class BookingService {
    private static final int MAX_SEATS = 8;
    private static final DefaultRedisScript<Long> RELEASE_HOLD = new DefaultRedisScript<>(
        "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> EXTEND_HOLD = new DefaultRedisScript<>(
        "for i=1,#KEYS do if redis.call('GET', KEYS[i]) ~= ARGV[1] then return 0 end end " +
        "for i=1,#KEYS do redis.call('PEXPIRE', KEYS[i], ARGV[2]) end return 1", Long.class);
    private static final DefaultRedisScript<Long> CLAIM_WAITLIST_SEATS = new DefaultRedisScript<>(
        "for i=1,#KEYS do if redis.call('GET',KEYS[i]) ~= ARGV[1] then return 0 end end " +
        "for i=1,#KEYS do redis.call('SET',KEYS[i],ARGV[2],'PX',ARGV[3]) end return 1", Long.class);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final StringRedisTemplate redis;
    private final RestClient paymentClient;
    private final ObjectMapper mapper;
    private final Duration holdTtl;
    private final String paymentToken;
    private final String callbackUrl;
    private final RealtimeService realtime;

    public BookingService(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, StringRedisTemplate redis,
                          RestClient paymentClient, ObjectMapper mapper,
                          @Value("${stagepass.hold-ttl:5m}") Duration holdTtl,
                          @Value("${stagepass.payment.service-token}") String paymentToken,
                          @Value("${stagepass.callback-url}") String callbackUrl, RealtimeService realtime) {
        this.jdbc = jdbc; this.namedJdbc = namedJdbc; this.redis = redis; this.paymentClient = paymentClient;
        this.mapper = mapper; this.holdTtl = holdTtl; this.paymentToken = paymentToken; this.callbackUrl = callbackUrl; this.realtime = realtime;
    }

    public record HoldResult(UUID bookingId, String showId, List<String> seatIds, String status, Instant expiresAt, int amountCents, String currency) {}
    public record ConfirmOutcome(UUID bookingId, String status, UUID paymentId, List<String> seatIds,
                                 String code, Integer amountCents, String currency, int httpStatus) {}
    public record BookingView(UUID bookingId, String showId, String status, List<String> seatIds,
                              Instant expiresAt, Instant createdAt) {}
    public record SeatView(String seatId, String sectionId, String sectionName, String row, int seatNumber,
                           int priceCents, String status) {}
    private record SeatPrice(String seatId, int priceCents) {}
    private record Session(UUID id, String showId, String userId, String state, UUID holdToken, UUID currentPaymentId, Instant expiresAt, long version) {
        Session withState(String newState, long newVersion) { return new Session(id, showId, userId, newState, holdToken, currentPaymentId, expiresAt, newVersion); }
        Session withPayment(UUID paymentId, long newVersion) { return new Session(id, showId, userId, state, holdToken, paymentId, expiresAt, newVersion); }
    }
    private record PaymentResponse(UUID paymentId, String status, String failureCode) {}
    private record PaymentCall(int httpStatus, PaymentResponse body) {}
    private record PaymentAttempt(Session session, UUID paymentId, String status, String userId, String idempotencyKey) {}

    @Transactional
    public HoldResult hold(String showId, String userId, List<String> requestedSeats) {
        if (requestedSeats == null || requestedSeats.isEmpty() || requestedSeats.size() > MAX_SEATS ||
            requestedSeats.stream().anyMatch(s -> s == null || s.isBlank()) || requestedSeats.stream().distinct().count() != requestedSeats.size()) {
            throw BookingException.badRequest("INVALID_SEAT_SELECTION", "Select between 1 and 8 distinct seat IDs.");
        }
        var seats = namedJdbc.query(
            "SELECT seat.id,sec.price_cents FROM catalog.shows sh JOIN catalog.sections sec ON sec.venue_id=sh.venue_id " +
                "JOIN catalog.seats seat ON seat.section_id=sec.id WHERE sh.id=:showId AND seat.id IN (:seatIds) ORDER BY seat.id",
            new MapSqlParameterSource().addValue("showId", showId).addValue("seatIds", requestedSeats),
            (rs, row) -> new SeatPrice(rs.getString("id"), rs.getInt("price_cents")));
        if (seats.size() != requestedSeats.size()) throw BookingException.notFound("SEAT_NOT_FOUND", "One or more seats do not belong to this show's venue.");

        Integer confirmed = namedJdbc.queryForObject(
            "SELECT count(*) FROM booking.confirmed_seats WHERE show_id=:showId AND seat_id IN (:seatIds)",
            new MapSqlParameterSource().addValue("showId", showId).addValue("seatIds", requestedSeats), Integer.class);
        if (confirmed != null && confirmed > 0) throw BookingException.conflict("SEAT_UNAVAILABLE", "One or more seats are already confirmed.");

        UUID bookingId = UUID.randomUUID();
        UUID holdToken = UUID.randomUUID();
        String redisValue = bookingId + ":" + holdToken;
        List<String> acquired = new ArrayList<>();
        try {
            for (SeatPrice seat : seats) {
                String key = holdKey(showId, seat.seatId());
                Boolean success = redis.opsForValue().setIfAbsent(key, redisValue, holdTtl);
                if (!Boolean.TRUE.equals(success)) throw BookingException.conflict("SEAT_HELD", "One or more seats are currently held by another booking.");
                acquired.add(key);
            }
            Instant expiresAt = Instant.now().plus(holdTtl);
            jdbc.update("INSERT INTO booking.sessions(id,show_id,user_id,state,hold_token,expires_at) VALUES(?,?,?,'HELD',?,?)",
                bookingId, showId, userId, holdToken, Timestamp.from(expiresAt));
            jdbc.update("INSERT INTO booking.transitions(booking_id,from_state,to_state,reason) VALUES(?,NULL,'HELD','seat hold created')", bookingId);
            for (SeatPrice seat : seats) jdbc.update("INSERT INTO booking.session_seats(booking_id,show_id,seat_id,price_cents) VALUES(?,?,?,?)",
                bookingId, showId, seat.seatId(), seat.priceCents());
            String currency = jdbc.queryForObject("SELECT currency FROM catalog.shows WHERE id=?", String.class, showId);
            int total = seats.stream().mapToInt(SeatPrice::priceCents).sum();
            realtime.recordEvent("seat.held", bookingId.toString(), Map.of("showId", showId, "seatIds", requestedSeats));
            return new HoldResult(bookingId, showId, seats.stream().map(SeatPrice::seatId).toList(), "HELD", expiresAt, total, currency);
        } catch (RuntimeException error) {
            acquired.forEach(key -> release(key, redisValue));
            throw error;
        }
    }

    @Transactional
    public HoldResult holdFromWaitlistOffer(String showId, String userId, RealtimeService.WaitlistOffer offer) {
        List<String> requestedSeats = offer.seatIds();
        if (requestedSeats.isEmpty() || requestedSeats.size() > MAX_SEATS) throw BookingException.conflict("INVALID_WAITLIST_OFFER", "The seat offer is invalid.");
        var seats = namedJdbc.query(
            "SELECT seat.id,sec.price_cents FROM catalog.shows sh JOIN catalog.sections sec ON sec.venue_id=sh.venue_id " +
                "JOIN catalog.seats seat ON seat.section_id=sec.id WHERE sh.id=:showId AND seat.id IN (:seatIds) ORDER BY seat.id",
            new MapSqlParameterSource().addValue("showId", showId).addValue("seatIds", requestedSeats),
            (rs, row) -> new SeatPrice(rs.getString("id"), rs.getInt("price_cents")));
        if (seats.size() != requestedSeats.size()) throw BookingException.conflict("INVALID_WAITLIST_OFFER", "One or more offered seats no longer exist.");
        Integer confirmed = namedJdbc.queryForObject(
            "SELECT count(*) FROM booking.confirmed_seats WHERE show_id=:showId AND seat_id IN (:seatIds)",
            new MapSqlParameterSource().addValue("showId", showId).addValue("seatIds", requestedSeats), Integer.class);
        if (confirmed != null && confirmed > 0) throw BookingException.conflict("SEAT_UNAVAILABLE", "One or more offered seats have already been sold.");

        UUID bookingId = UUID.randomUUID();
        UUID holdToken = UUID.randomUUID();
        String redisValue = bookingId + ":" + holdToken;
        String offerValue = "WAITLIST:" + offer.offerId() + ":" + sha256(userId);
        List<String> keys = requestedSeats.stream().map(seat -> holdKey(showId, seat)).toList();
        List<String> acquired = new ArrayList<>();
        try {
            Long claimed = redis.execute(CLAIM_WAITLIST_SEATS, keys, offerValue, redisValue, Long.toString(holdTtl.toMillis()));
            if (!Long.valueOf(1).equals(claimed)) throw BookingException.conflict("WAITLIST_OFFER_EXPIRED", "The offered seats are no longer reserved.");
            acquired.addAll(keys);
            Instant expiresAt = Instant.now().plus(holdTtl);
            jdbc.update("INSERT INTO booking.sessions(id,show_id,user_id,state,hold_token,expires_at) VALUES(?,?,?,'HELD',?,?)",
                bookingId, showId, userId, holdToken, Timestamp.from(expiresAt));
            jdbc.update("INSERT INTO booking.transitions(booking_id,from_state,to_state,reason) VALUES(?,NULL,'HELD','waitlist offer accepted')", bookingId);
            for (SeatPrice seat : seats) jdbc.update("INSERT INTO booking.session_seats(booking_id,show_id,seat_id,price_cents) VALUES(?,?,?,?)",
                bookingId, showId, seat.seatId(), seat.priceCents());
            String currency = jdbc.queryForObject("SELECT currency FROM catalog.shows WHERE id=?", String.class, showId);
            int total = seats.stream().mapToInt(SeatPrice::priceCents).sum();
            realtime.recordEvent("seat.held", bookingId.toString(), Map.of("showId", showId, "seatIds", requestedSeats));
            return new HoldResult(bookingId, showId, requestedSeats, "HELD", expiresAt, total, currency);
        } catch (RuntimeException error) {
            acquired.forEach(key -> release(key, redisValue));
            throw error;
        }
    }

    @Transactional
    public ConfirmOutcome confirm(UUID bookingId, String userId, String idempotencyKey, String methodToken) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128 || methodToken == null || methodToken.isBlank())
            throw BookingException.badRequest("INVALID_CONFIRMATION", "Idempotency-Key and paymentMethodToken are required.");
        String requestHash = sha256(bookingId + "\n" + methodToken);
        jdbc.update("INSERT INTO booking.idempotency_records(user_id,idempotency_key,request_hash,status) VALUES(?,?,?,'PROCESSING') ON CONFLICT DO NOTHING",
            userId, idempotencyKey, requestHash);
        var idem = jdbc.queryForMap("SELECT request_hash,status,response_json::text AS response_json FROM booking.idempotency_records WHERE user_id=? AND idempotency_key=? FOR UPDATE", userId, idempotencyKey);
        if (!requestHash.equals(idem.get("request_hash"))) throw BookingException.conflict("IDEMPOTENCY_KEY_REUSED", "This Idempotency-Key was already used for a different request.");
        if ("COMPLETED".equals(idem.get("status"))) return readOutcome((String) idem.get("response_json"));

        Session session = loadSessionForUpdate(bookingId);
        if (!session.userId().equals(userId)) throw BookingException.notFound("BOOKING_NOT_FOUND", "Booking was not found.");
        List<String> seatIds = jdbc.query("SELECT seat_id FROM booking.session_seats WHERE booking_id=? ORDER BY seat_id", (rs, row) -> rs.getString(1), bookingId);
        if (seatIds.isEmpty()) throw BookingException.conflict("BOOKING_HAS_NO_SEATS", "Booking has no seats.");
        if (Instant.now().isAfter(session.expiresAt()) && (session.state().equals("HELD") || session.state().equals("PAYMENT_FAILED"))) {
            BookingState old = BookingStates.from(session.state());
            Session expiredSession = persistTransition(session, old, old.timeout(), "hold expired before payment");
            scheduleRelease(expiredSession, seatIds);
            ConfirmOutcome outcome = new ConfirmOutcome(bookingId, "EXPIRED", null, seatIds, "HOLD_EXPIRED", null, null, 410);
            completeIdempotency(userId, idempotencyKey, outcome);
            return outcome;
        }
        BookingState old = BookingStates.from(session.state());
        BookingState pending = old.pay();
        String heldValue = bookingId + ":" + session.holdToken();
        for (String seatId : seatIds) {
            if (!heldValue.equals(redis.opsForValue().get(holdKey(session.showId(), seatId)))) {
                BookingState expired = old.timeout();
                Session expiredSession = persistTransition(session, old, expired, "redis hold is absent or owned by a different token");
                scheduleRelease(expiredSession, seatIds);
                ConfirmOutcome outcome = new ConfirmOutcome(bookingId, "EXPIRED", null, seatIds, "HOLD_EXPIRED", null, null, 410);
                completeIdempotency(userId, idempotencyKey, outcome);
                return outcome;
            }
        }
        Session pendingSession = persistTransition(session, old, pending, "payment started");

        Integer amountCents = jdbc.queryForObject("SELECT sum(price_cents)::int FROM booking.session_seats WHERE booking_id=?", Integer.class, bookingId);
        String currency = jdbc.queryForObject("SELECT currency FROM catalog.shows WHERE id=?", String.class, session.showId());
        PaymentCall payment = createPayment(session, amountCents, currency, methodToken, idempotencyKey);
        if (payment.body() == null || payment.body().paymentId() == null) throw new BookingException("PAYMENT_UNAVAILABLE", "Payment service returned an invalid response.", HttpStatus.SERVICE_UNAVAILABLE);
        if ("PENDING".equals(payment.body().status())) {
            int changed = jdbc.update("UPDATE booking.sessions SET current_payment_id=?,version=version+1,updated_at=now() WHERE id=? AND version=?",
                payment.body().paymentId(), bookingId, pendingSession.version());
            if (changed != 1) throw BookingException.conflict("BOOKING_CONCURRENT_UPDATE", "Booking changed while payment was being started.");
            jdbc.update("INSERT INTO booking.payment_attempts(payment_id,booking_id,user_id,idempotency_key,status) VALUES(?,?,?,?,'PENDING')",
                payment.body().paymentId(), bookingId, userId, idempotencyKey);
            ConfirmOutcome outcome = new ConfirmOutcome(bookingId, "PAYMENT_PENDING", payment.body().paymentId(), seatIds, null, amountCents, currency, 202);
            completeIdempotency(userId, idempotencyKey, outcome);
            return outcome;
        }
        if (!"SUCCEEDED".equals(payment.body().status())) {
            BookingState failed = pending.paymentFailed();
            persistTransition(pendingSession, pending, failed, payment.body().failureCode() == null ? "payment failed" : payment.body().failureCode());
            ConfirmOutcome outcome = new ConfirmOutcome(bookingId, "PAYMENT_FAILED", payment.body().paymentId(), seatIds, payment.body().failureCode(), amountCents, currency, 402);
            completeIdempotency(userId, idempotencyKey, outcome);
            return outcome;
        }

        int inserted = 0;
        for (String seatId : seatIds) {
            inserted += jdbc.update("INSERT INTO booking.confirmed_seats(show_id,seat_id,booking_id) VALUES(?,?,?) ON CONFLICT(show_id,seat_id) DO NOTHING",
                session.showId(), seatId, bookingId);
        }
        if (inserted != seatIds.size()) {
            refund(payment.body().paymentId(), "compensate:" + sha256(idempotencyKey).substring(0, 32));
            jdbc.update("DELETE FROM booking.confirmed_seats WHERE booking_id=?", bookingId);
            BookingState cancelled = pending.cancel();
            Session cancelledSession = persistTransition(pendingSession, pending, cancelled, "seat confirmation lost a database uniqueness race; payment compensated");
            ConfirmOutcome outcome = new ConfirmOutcome(bookingId, "CANCELLED", payment.body().paymentId(), seatIds, "SEAT_UNAVAILABLE", amountCents, currency, 409);
            completeIdempotency(userId, idempotencyKey, outcome);
            scheduleRelease(cancelledSession, seatIds);
            return outcome;
        }
        BookingState confirmed = pending.paymentSucceeded();
        Session confirmedSession = persistTransition(pendingSession, pending, confirmed, "payment succeeded and seats committed");
        realtime.recordEvent("booking.confirmed", bookingId.toString(), Map.of("showId", session.showId(), "seatIds", seatIds));
        ConfirmOutcome outcome = new ConfirmOutcome(bookingId, "CONFIRMED", payment.body().paymentId(), seatIds, null, amountCents, currency, 200);
        completeIdempotency(userId, idempotencyKey, outcome);
        scheduleRelease(confirmedSession, seatIds);
        return outcome;
    }

    @Transactional
    public Map<String, Object> cancel(UUID bookingId, String userId) {
        Session session = loadSessionForUpdate(bookingId);
        if (!session.userId().equals(userId)) throw BookingException.notFound("BOOKING_NOT_FOUND", "Booking was not found.");
        BookingState old = BookingStates.from(session.state());
        Session cancelled = persistTransition(session, old, old.cancel(), "cancelled by owner");
        List<String> seatIds = jdbc.query("SELECT seat_id FROM booking.session_seats WHERE booking_id=?", (rs, row) -> rs.getString(1), bookingId);
        realtime.recordEvent("booking.cancelled", bookingId.toString(), Map.of("showId", session.showId(), "seatIds", seatIds));
        scheduleRelease(cancelled, seatIds);
        return Map.of("bookingId", bookingId, "status", cancelled.state());
    }

    @Transactional
    public Instant extend(UUID bookingId, String userId) {
        Session session = loadSessionForUpdate(bookingId);
        if (!session.userId().equals(userId)) throw BookingException.notFound("BOOKING_NOT_FOUND", "Booking was not found.");
        if (!(session.state().equals("HELD") || session.state().equals("PAYMENT_FAILED")) || Instant.now().isAfter(session.expiresAt()))
            throw BookingException.conflict("HOLD_NOT_EXTENDABLE", "This booking hold is no longer active.");
        List<String> seats = jdbc.query("SELECT seat_id FROM booking.session_seats WHERE booking_id=? ORDER BY seat_id", (rs, row) -> rs.getString(1), bookingId);
        List<String> keys = seats.stream().map(seat -> holdKey(session.showId(), seat)).toList();
        String value = bookingId + ":" + session.holdToken();
        Long extended = redis.execute(EXTEND_HOLD, keys, value, Long.toString(holdTtl.toMillis()));
        if (!Long.valueOf(1).equals(extended)) throw BookingException.conflict("HOLD_EXPIRED", "The hold expired or its ownership token no longer matches.");
        Instant newExpiry = Instant.now().plus(holdTtl);
        int changed = jdbc.update("UPDATE booking.sessions SET expires_at=?,version=version+1,updated_at=now() WHERE id=? AND version=?",
            Timestamp.from(newExpiry), bookingId, session.version());
        if (changed != 1) throw BookingException.conflict("BOOKING_CONCURRENT_UPDATE", "Booking changed while this request was being applied.");
        return newExpiry;
    }

    @Scheduled(fixedDelayString = "${stagepass.booking.expiry-sweep-ms:5000}")
    @Transactional
    public void expireDueBookings() {
        List<Session> due = jdbc.query(
            "SELECT id,show_id,user_id,state,hold_token,current_payment_id,expires_at,version FROM booking.sessions " +
                "WHERE state IN ('HELD','PAYMENT_FAILED','PAYMENT_PENDING') AND expires_at<=now() " +
                "ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED",
            (rs, row) -> new Session(rs.getObject("id", UUID.class), rs.getString("show_id"), rs.getString("user_id"), rs.getString("state"),
                rs.getObject("hold_token", UUID.class), rs.getObject("current_payment_id", UUID.class), rs.getTimestamp("expires_at").toInstant(), rs.getLong("version")));
        for (Session session : due) {
            BookingState old = BookingStates.from(session.state());
            Session expired = persistTransition(session, old, old.timeout(), "hold expired by background cleanup");
            List<String> seats = jdbc.query("SELECT seat_id FROM booking.session_seats WHERE booking_id=? ORDER BY seat_id", (rs, row) -> rs.getString(1), session.id());
            scheduleRelease(expired, seats);
        }
    }

    @Transactional
    public void receivePaymentCallback(UUID paymentId, UUID bookingId, String callbackStatus) {
        if (!List.of("SUCCEEDED", "FAILED").contains(callbackStatus))
            throw BookingException.badRequest("INVALID_PAYMENT_CALLBACK", "Unsupported payment callback status.");
        PaymentAttempt attempt;
        try {
            attempt = jdbc.queryForObject(
                "SELECT s.id,s.show_id,s.user_id,s.state,s.hold_token,s.current_payment_id,s.expires_at,s.version," +
                    "a.payment_id,a.status AS attempt_status,a.idempotency_key " +
                    "FROM booking.payment_attempts a JOIN booking.sessions s ON s.id=a.booking_id " +
                    "WHERE a.payment_id=? AND a.booking_id=? FOR UPDATE OF a,s",
                (rs, row) -> new PaymentAttempt(
                    new Session(rs.getObject("id", UUID.class), rs.getString("show_id"), rs.getString("user_id"), rs.getString("state"),
                        rs.getObject("hold_token", UUID.class), rs.getObject("current_payment_id", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(), rs.getLong("version")),
                    rs.getObject("payment_id", UUID.class), rs.getString("attempt_status"), rs.getString("user_id"), rs.getString("idempotency_key")),
                paymentId, bookingId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw BookingException.notFound("PAYMENT_ATTEMPT_NOT_FOUND", "Payment attempt was not found.");
        }
        if (!"PENDING".equals(attempt.status())) return;
        Session session = attempt.session();
        List<String> seatIds = jdbc.query("SELECT seat_id FROM booking.session_seats WHERE booking_id=? ORDER BY seat_id", (rs, row) -> rs.getString(1), bookingId);
        boolean currentAttempt = paymentId.equals(session.currentPaymentId());

        if ("FAILED".equals(callbackStatus)) {
            if (currentAttempt && "PAYMENT_PENDING".equals(session.state())) {
                if (Instant.now().isAfter(session.expiresAt())) {
                    session = persistTransition(session, BookingStates.from(session.state()), BookingStates.from(session.state()).timeout(), "payment failed after hold expiry");
                    scheduleRelease(session, seatIds);
                    jdbc.update("UPDATE booking.payment_attempts SET status='FAILED',updated_at=now() WHERE payment_id=?", paymentId);
                    completeIdempotency(attempt.userId(), attempt.idempotencyKey(), outcome(session, paymentId, seatIds, "PAYMENT_FAILED", "HOLD_EXPIRED", 410));
                } else {
                    BookingState old = BookingStates.from(session.state());
                    session = persistTransition(session, old, old.paymentFailed(), "asynchronous payment failed");
                    jdbc.update("UPDATE booking.payment_attempts SET status='FAILED',updated_at=now() WHERE payment_id=?", paymentId);
                    completeIdempotency(attempt.userId(), attempt.idempotencyKey(), outcome(session, paymentId, seatIds, "PAYMENT_FAILED", "PAYMENT_FAILED", 402));
                }
            } else {
                jdbc.update("UPDATE booking.payment_attempts SET status='FAILED',updated_at=now() WHERE payment_id=?", paymentId);
            }
            return;
        }

        boolean active = currentAttempt && "PAYMENT_PENDING".equals(session.state()) && !Instant.now().isAfter(session.expiresAt());
        String heldValue = bookingId + ":" + session.holdToken();
        if (active) {
            for (String seatId : seatIds) {
                if (!heldValue.equals(redis.opsForValue().get(holdKey(session.showId(), seatId)))) { active = false; break; }
            }
        }
        if (!active) {
            if (currentAttempt && "PAYMENT_PENDING".equals(session.state()) && Instant.now().isAfter(session.expiresAt())) {
                session = persistTransition(session, BookingStates.from(session.state()), BookingStates.from(session.state()).timeout(), "payment succeeded after hold expiry");
                scheduleRelease(session, seatIds);
            }
            refund(paymentId, "late:" + sha256(paymentId.toString()).substring(0, 32));
            jdbc.update("UPDATE booking.payment_attempts SET status='REFUNDED',updated_at=now() WHERE payment_id=?", paymentId);
            String state = session.state().equals("EXPIRED") ? "EXPIRED" : session.state();
            int httpStatus = state.equals("EXPIRED") ? 410 : 409;
            completeIdempotency(attempt.userId(), attempt.idempotencyKey(), outcome(session, paymentId, seatIds, state, "PAYMENT_SUCCEEDED_AFTER_BOOKING_ENDED", httpStatus));
            return;
        }

        int inserted = 0;
        for (String seatId : seatIds) inserted += jdbc.update(
            "INSERT INTO booking.confirmed_seats(show_id,seat_id,booking_id) VALUES(?,?,?) ON CONFLICT(show_id,seat_id) DO NOTHING",
            session.showId(), seatId, bookingId);
        if (inserted != seatIds.size()) {
            jdbc.update("DELETE FROM booking.confirmed_seats WHERE booking_id=?", bookingId);
            refund(paymentId, "late:" + sha256(paymentId.toString()).substring(0, 32));
            Session cancelled = persistTransition(session, BookingStates.from(session.state()), BookingStates.from(session.state()).cancel(), "late payment success lost the confirmed-seat race; payment compensated");
            jdbc.update("UPDATE booking.payment_attempts SET status='REFUNDED',updated_at=now() WHERE payment_id=?", paymentId);
            completeIdempotency(attempt.userId(), attempt.idempotencyKey(), outcome(cancelled, paymentId, seatIds, "CANCELLED", "SEAT_UNAVAILABLE", 409));
            scheduleRelease(cancelled, seatIds);
            return;
        }
        Session confirmed = persistTransition(session, BookingStates.from(session.state()), BookingStates.from(session.state()).paymentSucceeded(), "asynchronous payment succeeded");
        realtime.recordEvent("booking.confirmed", bookingId.toString(), Map.of("showId", session.showId(), "seatIds", seatIds));
        jdbc.update("UPDATE booking.payment_attempts SET status='SUCCEEDED',updated_at=now() WHERE payment_id=?", paymentId);
        completeIdempotency(attempt.userId(), attempt.idempotencyKey(), outcome(confirmed, paymentId, seatIds, "CONFIRMED", null, 200));
        scheduleRelease(confirmed, seatIds);
    }

    public List<BookingView> listForUser(String userId) {
        return jdbc.query("SELECT id,show_id,state,expires_at,created_at FROM booking.sessions WHERE user_id=? ORDER BY created_at DESC",
            (rs, row) -> new BookingView(rs.getObject("id", UUID.class), rs.getString("show_id"), rs.getString("state"),
                jdbc.query("SELECT seat_id FROM booking.session_seats WHERE booking_id=? ORDER BY seat_id", (s, i) -> s.getString(1), rs.getObject("id", UUID.class)),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant()), userId);
    }

    public List<SeatView> seatsForShow(String showId) {
        var seats = jdbc.query(
            "SELECT seat.id,sec.id AS section_id,sec.name AS section_name,seat.row_label,seat.seat_number,sec.price_cents, " +
                "(confirmed.seat_id IS NOT NULL) AS confirmed " +
                "FROM catalog.shows sh JOIN catalog.sections sec ON sec.venue_id=sh.venue_id " +
                "JOIN catalog.seats seat ON seat.section_id=sec.id " +
                "LEFT JOIN booking.confirmed_seats confirmed ON confirmed.show_id=sh.id AND confirmed.seat_id=seat.id " +
                "WHERE sh.id=? ORDER BY sec.id,seat.row_label,seat.seat_number",
            (rs, row) -> new Object[] { rs.getString("id"), rs.getString("section_id"), rs.getString("section_name"),
                rs.getString("row_label"), rs.getInt("seat_number"), rs.getInt("price_cents"), rs.getBoolean("confirmed") }, showId);
        if (seats.isEmpty()) throw BookingException.notFound("SHOW_NOT_FOUND", "Show was not found.");
        List<SeatView> result = new ArrayList<>();
        for (Object[] seat : seats) {
            String id = (String) seat[0];
            boolean confirmed = (boolean) seat[6];
            String status = confirmed ? "CONFIRMED" : (Boolean.TRUE.equals(redis.hasKey(holdKey(showId, id))) ? "HELD" : "AVAILABLE");
            result.add(new SeatView(id, (String) seat[1], (String) seat[2], (String) seat[3], (int) seat[4], (int) seat[5], status));
        }
        return result;
    }

    private Session loadSessionForUpdate(UUID id) {
        try {
        return jdbc.queryForObject("SELECT id,show_id,user_id,state,hold_token,current_payment_id,expires_at,version FROM booking.sessions WHERE id=? FOR UPDATE",
                (rs, row) -> new Session(rs.getObject("id", UUID.class), rs.getString("show_id"), rs.getString("user_id"), rs.getString("state"),
                    rs.getObject("hold_token", UUID.class), rs.getObject("current_payment_id", UUID.class), rs.getTimestamp("expires_at").toInstant(), rs.getLong("version")), id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) { throw BookingException.notFound("BOOKING_NOT_FOUND", "Booking was not found."); }
    }

    private Session persistTransition(Session session, BookingState from, BookingState to, String reason) {
        int changed = jdbc.update("UPDATE booking.sessions SET state=?,version=version+1,updated_at=now() WHERE id=? AND version=?",
            to.name(), session.id(), session.version());
        if (changed != 1) throw BookingException.conflict("BOOKING_CONCURRENT_UPDATE", "Booking changed while this request was being applied.");
        jdbc.update("INSERT INTO booking.transitions(booking_id,from_state,to_state,reason) VALUES(?,?,?,?)",
            session.id(), from.name(), to.name(), reason);
        return session.withState(to.name(), session.version() + 1);
    }

    private PaymentCall createPayment(Session session, int amountCents, String currency, String methodToken, String key) {
        return paymentClient.post().uri("/internal/payments")
            .header("X-Service-Token", paymentToken)
            .header("Idempotency-Key", "booking:" + session.id() + ":" + sha256(key).substring(0, 32))
            .body(Map.of("bookingId", session.id(), "userId", session.userId(), "amountCents", amountCents,
                "currency", currency, "methodToken", methodToken, "callbackUrl", callbackUrl))
            .exchange((request, response) -> new PaymentCall(response.getStatusCode().value(), mapper.readValue(response.getBody(), PaymentResponse.class)));
    }

    private void refund(UUID paymentId, String key) {
        try {
            paymentClient.post().uri("/internal/payments/{id}/refund", paymentId)
                .header("X-Service-Token", paymentToken).header("Idempotency-Key", key).retrieve().toBodilessEntity();
        } catch (RuntimeException error) {
            throw new BookingException("PAYMENT_COMPENSATION_FAILED", "Seat commit failed and the payment refund could not be confirmed; retry the same confirmation key.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    private void completeIdempotency(String userId, String key, ConfirmOutcome outcome) {
        try {
            String json = mapper.writeValueAsString(outcome);
            jdbc.update("UPDATE booking.idempotency_records SET status='COMPLETED',response_json=?::jsonb,http_status=?,updated_at=now() WHERE user_id=? AND idempotency_key=?",
                json, outcome.httpStatus(), userId, key);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Could not persist idempotent response", e); }
    }

    private ConfirmOutcome outcome(Session session, UUID paymentId, List<String> seats, String status, String code, int httpStatus) {
        Integer amount = jdbc.queryForObject("SELECT sum(price_cents)::int FROM booking.session_seats WHERE booking_id=?", Integer.class, session.id());
        String currency = jdbc.queryForObject("SELECT currency FROM catalog.shows WHERE id=?", String.class, session.showId());
        return new ConfirmOutcome(session.id(), status, paymentId, seats, code, amount, currency, httpStatus);
    }

    private ConfirmOutcome readOutcome(String json) {
        try { return mapper.readValue(json, ConfirmOutcome.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Stored idempotency response is invalid", e); }
    }

    private void scheduleRelease(Session session, List<String> seats) {
        String value = session.id() + ":" + session.holdToken();
        if ("EXPIRED".equals(session.state())) realtime.recordEvent("booking.expired", session.id().toString(), Map.of("showId", session.showId(), "seatIds", seats));
        seats.forEach(seat -> realtime.recordEvent("CONFIRMED".equals(session.state()) ? "seat.booked" : "seat.released",
            session.id().toString(), Map.of("showId", session.showId(), "seatId", seat)));
        Runnable action = () -> seats.forEach(seat -> {
            String key = holdKey(session.showId(), seat);
            boolean unlocked = release(key, value);
            Boolean confirmed = jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM booking.confirmed_seats WHERE show_id=? AND seat_id=?)",
                Boolean.class, session.showId(), seat);
            if (!"CONFIRMED".equals(session.state()) && !Boolean.TRUE.equals(confirmed) && (unlocked || !Boolean.TRUE.equals(redis.hasKey(key))))
                realtime.seatReleased(session.showId(), seat);
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { action.run(); }
            });
        } else action.run();
    }

    private boolean release(String key, String value) { return Long.valueOf(1).equals(redis.execute(RELEASE_HOLD, List.of(key), value)); }
    private String holdKey(String showId, String seatId) { return "stagepass:hold:" + showId + ":" + seatId; }
    private String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
