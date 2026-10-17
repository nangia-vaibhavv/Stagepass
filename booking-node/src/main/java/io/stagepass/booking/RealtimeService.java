package io.stagepass.booking;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class RealtimeService implements MessageListener {
    private static final String STREAM = "stagepass:events";
    private static final String TOPIC = "stagepass:events:live";
    private static final Duration PRESENCE_WINDOW = Duration.ofSeconds(30);
    private static final Duration OFFER_TTL = Duration.ofSeconds(60);
    private static final Duration ADMISSION_RATE_WINDOW = Duration.ofMinutes(15);
    private static final org.springframework.data.redis.core.script.DefaultRedisScript<Long> JOIN_WAITLIST = new org.springframework.data.redis.core.script.DefaultRedisScript<>(
        "local rank=redis.call('ZRANK',KEYS[1],ARGV[1]); if rank then return rank+1 end; " +
        "local seq=redis.call('INCR',KEYS[2]); redis.call('ZADD',KEYS[1],'NX',seq,ARGV[1]); " +
        "return redis.call('ZRANK',KEYS[1],ARGV[1])+1", Long.class);
    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public RealtimeService(StringRedisTemplate redis, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.redis = redis; this.jdbc = jdbc; this.mapper = mapper;
    }

    public record DomainEvent(String eventId, String eventType, String aggregateId, Instant occurredAt,
                              int schemaVersion, Map<String, Object> data) {}
    public record PresenceCount(int activeViewers, int activeWindowSeconds) {}
    public record WaitlistPosition(String showId, int quantity, long position, long estimatedWaitSeconds,
                                   List<String> offeredSeatIds, long offerExpiresAtEpochMillis) {}
    public record WaitlistOffer(String showId, String offerId, List<String> seatIds, long expiresAtEpochMillis) {}
    private record AvailableSeat(String seatId) {}

    public void recordEvent(String eventType, String aggregateId, Map<String, Object> data) {
        try {
            jdbc.update("INSERT INTO booking.outbox_events(event_id,event_type,aggregate_id,occurred_at,schema_version,data) VALUES(?,?,?,?,1,?::jsonb)",
                UUID.randomUUID(), eventType, aggregateId, TimestampNow.value(), mapper.writeValueAsString(data));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Could not serialize domain event", e); }
    }

    @Scheduled(fixedDelayString = "${stagepass.events.relay-delay-ms:500}")
    public void relayOutbox() {
        var rows = jdbc.query("SELECT event_id,event_type,aggregate_id,occurred_at,schema_version,data::text AS data FROM booking.outbox_events WHERE published_at IS NULL ORDER BY created_at LIMIT 100",
            (rs, row) -> new DomainEvent(rs.getObject("event_id", UUID.class).toString(), rs.getString("event_type"),
                rs.getString("aggregate_id"), rs.getTimestamp("occurred_at").toInstant(), rs.getInt("schema_version"), readMap(rs.getString("data"))));
        for (DomainEvent event : rows) {
            Map<String, String> fields = Map.of("eventId", event.eventId(), "eventType", event.eventType(), "aggregateId", event.aggregateId(),
                "occurredAt", event.occurredAt().toString(), "schemaVersion", Integer.toString(event.schemaVersion()), "data", write(event.data()));
            try {
                redis.opsForStream().add(MapRecord.create(STREAM, fields));
                redis.convertAndSend(TOPIC, write(event));
                jdbc.update("UPDATE booking.outbox_events SET published_at=now() WHERE event_id=? AND published_at IS NULL", UUID.fromString(event.eventId()));
            } catch (RuntimeException ignored) { return; }
        }
    }

    public PresenceCount heartbeat(String showId, String viewerId) {
        long now = System.currentTimeMillis();
        String key = "stagepass:presence:" + showId;
        redis.opsForZSet().add(key, opaque(viewerId), now);
        redis.expire(key, Duration.ofMinutes(2));
        PresenceCount count = countPresence(showId);
        recordEvent("presence.count_updated", showId, Map.of("showId", showId, "activeViewers", count.activeViewers()));
        return count;
    }

    public PresenceCount countPresence(String showId) {
        long cutoff = System.currentTimeMillis() - PRESENCE_WINDOW.toMillis();
        String key = "stagepass:presence:" + showId;
        redis.opsForZSet().removeRangeByScore(key, 0, cutoff);
        Long count = redis.opsForZSet().zCard(key);
        return new PresenceCount(count == null ? 0 : count.intValue(), (int) PRESENCE_WINDOW.toSeconds());
    }

    public WaitlistPosition join(String showId, int quantity, String userId) {
        if (quantity < 1 || quantity > 8) throw BookingException.badRequest("INVALID_WAITLIST_QUANTITY", "Choose between 1 and 8 seats.");
        String member = opaque(userId);
        Map<Object, Object> activeOffer = redis.opsForHash().entries(offerKey(showId, member));
        if (!activeOffer.isEmpty() && parseLong(activeOffer.get("expiresAt")) > System.currentTimeMillis()) return position(showId, userId);
        String key = waitlistKey(showId);
        Long rank = redis.execute(JOIN_WAITLIST, List.of(key, key + ":sequence"), member);
        redis.opsForHash().put(waitlistQuantitiesKey(showId), member, Integer.toString(quantity));
        allocateWaitlist(showId, null);
        return position(showId, userId);
    }

    public WaitlistPosition position(String showId, String userId) {
        String member = opaque(userId);
        String key = waitlistKey(showId);
        Long rank = redis.opsForZSet().rank(key, member);
        Map<Object, Object> fields = redis.opsForHash().entries(offerKey(showId, member));
        List<String> offered = fields.isEmpty() ? List.of() : readStringList(fields.get("seatIds"));
        long expiresAt = parseLong(fields.get("expiresAt"));
        Object quantity = redis.opsForHash().get(waitlistQuantitiesKey(showId), member);
        if (rank == null && offered.isEmpty()) throw BookingException.notFound("WAITLIST_ENTRY_NOT_FOUND", "No active waitlist entry or offer was found for this user.");
        long position = rank == null ? 0 : rank + 1;
        int requested = quantity == null ? offered.size() : Integer.parseInt(quantity.toString());
        return new WaitlistPosition(showId, requested, position, estimateWait(showId, position), offered, expiresAt);
    }

    /* compatibility with previously persisted callers; show-wide queues intentionally ignore seat IDs */
    @Deprecated
    public WaitlistPosition join(String showId, String ignoredSeatId, String userId) { return join(showId, 1, userId); }
    @Deprecated
    public WaitlistPosition position(String showId, String ignoredSeatId, String userId) { return position(showId, userId); }

    public void setMode(String showId, String mode) {
        if (modeFor(mode) == null) throw BookingException.badRequest("INVALID_WAITLIST_MODE", "Use OFFER_NEXT, RETRY_CURRENT, ROUND_ROBIN, or RELEASE_TO_POOL.");
        redis.opsForValue().set("stagepass:waitlist:mode:" + showId, mode);
    }

    public String mode(String showId) {
        String value = redis.opsForValue().get("stagepass:waitlist:mode:" + showId);
        return value == null ? "OFFER_NEXT" : value;
    }

    public SseEmitter subscribe(String showId) {
        long total = emitters.values().stream().mapToLong(List::size).sum();
        if (total >= 1000) throw new BookingException("SSE_CAPACITY", "This booking node has reached its stream connection limit.", org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(5).toMillis());
        var clients = emitters.computeIfAbsent(showId, ignored -> new CopyOnWriteArrayList<>());
        clients.add(emitter);
        Runnable remove = () -> { clients.remove(emitter); if (clients.isEmpty()) emitters.remove(showId, clients); };
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(error -> remove.run());
        try { emitter.send(SseEmitter.event().name("connected").data(Map.of("showId", showId, "refreshSnapshot", true))); }
        catch (Exception error) { remove.run(); emitter.completeWithError(error); }
        return emitter;
    }

    @Override public void onMessage(Message message, byte[] pattern) {
        try {
            DomainEvent event = mapper.readValue(message.getBody(), DomainEvent.class);
            String showId = event.data().get("showId") == null ? null : event.data().get("showId").toString();
            if (showId == null) return;
            var clients = emitters.get(showId);
            if (clients == null) return;
            for (SseEmitter emitter : clients) {
                try { emitter.send(SseEmitter.event().id(event.eventId()).name(event.eventType()).data(event)); }
                catch (Exception error) { clients.remove(emitter); emitter.completeWithError(error); }
            }
        } catch (Exception ignored) { }
    }

    @Scheduled(fixedDelay = 10000)
    public void keepStreamsAlive() {
        emitters.forEach((showId, clients) -> clients.forEach(emitter -> {
            try { emitter.send(SseEmitter.event().comment("keepalive")); }
            catch (Exception error) { clients.remove(emitter); emitter.completeWithError(error); }
        }));
    }

    public void seatReleased(String showId, String seatId) { allocateWaitlist(showId, null); }

    public WaitlistOffer claimOffer(String showId, String userId) {
        String member = opaque(userId);
        Map<Object, Object> fields = redis.opsForHash().entries(offerKey(showId, member));
        if (fields.isEmpty()) return null;
        String offerId = Objects.toString(fields.get("offerId"), "");
        long expiresAt = parseLong(fields.get("expiresAt"));
        if (expiresAt <= System.currentTimeMillis()) return null;
        List<String> seatIds = readStringList(fields.get("seatIds"));
        if (seatIds.isEmpty()) return null;
        String claim = "stagepass:waitlist:claim:" + showId + ":" + member;
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(claim, offerId, Duration.ofSeconds(10)))) return null;
        return new WaitlistOffer(showId, offerId, seatIds, expiresAt);
    }

    public void completeOffer(String showId, String userId, WaitlistOffer offer) {
        String member = opaque(userId);
        redis.delete(offerKey(showId, member));
        redis.delete("stagepass:waitlist:claim:" + showId + ":" + member);
        redis.opsForSet().remove("stagepass:waitlist:offer-members:" + showId, member);
        redis.opsForHash().delete(waitlistQuantitiesKey(showId), member);
        for (String seatId : offer.seatIds()) releaseOfferHold(showId, seatId, offer.offerId(), member);
        String admissions = "stagepass:waitlist:admissions:" + showId;
        redis.opsForZSet().add(admissions, UUID.randomUUID().toString(), System.currentTimeMillis());
        redis.expire(admissions, Duration.ofMinutes(20));
    }

    public void restoreOffer(String showId, String userId, WaitlistOffer offer) {
        String member = opaque(userId);
        redis.delete("stagepass:waitlist:claim:" + showId + ":" + member);
        offer.seatIds().forEach(seat -> redis.opsForValue().setIfAbsent(holdKey(showId, seat), offerValue(offer.offerId(), member), Duration.ofSeconds(Math.max(1, (offer.expiresAtEpochMillis() - System.currentTimeMillis()) / 1000))));
    }

    @Scheduled(fixedDelayString = "${stagepass.waitlist.expiry-poll-ms:1000}")
    public void expireOffers() {
        long now = System.currentTimeMillis();
        for (String showId : jdbc.query("SELECT id FROM catalog.shows", (rs, row) -> rs.getString(1))) {
            var offerMembers = redis.opsForSet().members("stagepass:waitlist:offer-members:" + showId);
            for (String member : new ArrayList<>(offerMembers == null ? java.util.Set.<String>of() : offerMembers)) {
                Map<Object, Object> offer = redis.opsForHash().entries(offerKey(showId, member));
                if (offer.isEmpty() || parseLong(offer.get("expiresAt")) > now) continue;
                String offerId = Objects.toString(offer.get("offerId"), "");
                for (String seat : readStringList(offer.get("seatIds"))) releaseOfferHold(showId, seat, offerId, member);
                redis.delete(offerKey(showId, member));
                redis.opsForSet().remove("stagepass:waitlist:offer-members:" + showId, member);
                String policy = mode(showId);
                if ("OFFER_NEXT".equals(policy)) {
                    redis.opsForZSet().remove(waitlistKey(showId), member);
                    redis.opsForHash().delete(waitlistQuantitiesKey(showId), member);
                }
                else {
                    Long seq = redis.opsForValue().increment(waitlistKey(showId) + ":sequence");
                    redis.opsForZSet().add(waitlistKey(showId), member, seq == null ? now : seq.doubleValue());
                }
                recordEvent("waitlist.offer_expired", showId, Map.of("showId", showId, "seatIds", readStringList(offer.get("seatIds"))));
            }
            allocateWaitlist(showId, null);
        }
    }

    private void allocateWaitlist(String showId, String expiredMember) {
        String lockKey = "stagepass:waitlist:allocator:" + showId;
        String lockId = UUID.randomUUID().toString();
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, lockId, Duration.ofSeconds(5)))) return;
        try {
            var members = redis.opsForZSet().range(waitlistKey(showId), 0, -1);
            if (members == null || members.isEmpty()) return;
            var ordered = new ArrayList<>(members);
            var mode = modeFor(mode(showId));
            if (mode == null) return;
            String candidate = mode.next(ordered, expiredMember);
            if (candidate == null) return;
            Object quantityValue = redis.opsForHash().get(waitlistQuantitiesKey(showId), candidate);
            int quantity = quantityValue == null ? 1 : Integer.parseInt(quantityValue.toString());
            var available = jdbc.query("SELECT seat.id FROM catalog.shows sh JOIN catalog.seats seat ON seat.section_id IN (SELECT id FROM catalog.sections WHERE venue_id=sh.venue_id) LEFT JOIN booking.confirmed_seats cs ON cs.show_id=sh.id AND cs.seat_id=seat.id WHERE sh.id=? AND cs.seat_id IS NULL ORDER BY seat.id", (rs, row) -> rs.getString(1), showId);
            List<String> acquired = new ArrayList<>();
            String offerId = UUID.randomUUID().toString();
            for (String seatId : available) {
                if (acquired.size() >= quantity) break;
                Boolean set = redis.opsForValue().setIfAbsent(holdKey(showId, seatId), offerValue(offerId, candidate), OFFER_TTL);
                if (Boolean.TRUE.equals(set)) acquired.add(seatId);
            }
            if (acquired.size() < quantity) {
                acquired.forEach(seat -> releaseOfferHold(showId, seat, offerId, candidate));
                return;
            }
            long expiry = System.currentTimeMillis() + OFFER_TTL.toMillis();
            String offerKey = offerKey(showId, candidate);
            redis.opsForHash().putAll(offerKey, Map.of("offerId", offerId, "seatIds", write(acquired), "expiresAt", Long.toString(expiry)));
            redis.opsForSet().add("stagepass:waitlist:offer-members:" + showId, candidate);
            redis.opsForZSet().remove(waitlistKey(showId), candidate);
            recordEvent("waitlist.offered", showId, Map.of("showId", showId, "seatIds", acquired));
        } finally {
            redis.delete(lockKey);
        }
    }

    private String waitlistKey(String showId) { return "stagepass:waitlist:" + showId; }
    private String waitlistQuantitiesKey(String showId) { return "stagepass:waitlist:quantities:" + showId; }
    private String offerKey(String showId, String member) { return "stagepass:waitlist:offer:" + showId + ":" + member; }
    private String holdKey(String showId, String seatId) { return "stagepass:hold:" + showId + ":" + seatId; }
    private String offerValue(String offerId, String member) { return "WAITLIST:" + offerId + ":" + member; }
    private void releaseOfferHold(String showId, String seatId, String offerId, String member) {
        redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<Long>("if redis.call('GET',KEYS[1]) == ARGV[1] then return redis.call('DEL',KEYS[1]) else return 0 end", Long.class), List.of(holdKey(showId, seatId)), offerValue(offerId, member));
    }
    private List<String> readStringList(Object value) {
        if (value == null) return List.of();
        try { return mapper.readValue(value.toString(), mapper.getTypeFactory().constructCollectionType(List.class, String.class)); }
        catch (Exception error) { return List.of(); }
    }
    private long parseLong(Object value) { try { return Long.parseLong(Objects.toString(value, "0")); } catch (Exception error) { return 0; } }
    private long estimateWait(String showId, long position) {
        long ahead = Math.max(0, position - 1);
        if (ahead == 0) return 0;
        long now = System.currentTimeMillis();
        String key = "stagepass:waitlist:admissions:" + showId;
        redis.opsForZSet().removeRangeByScore(key, 0, now - ADMISSION_RATE_WINDOW.toMillis());
        Long recent = redis.opsForZSet().count(key, now - ADMISSION_RATE_WINDOW.toMillis(), now);
        long admissions = recent == null ? 0 : recent;
        return admissions == 0 ? ahead * 60 : (long) Math.ceil(ahead * ADMISSION_RATE_WINDOW.toSeconds() / (double) admissions);
    }
    private WaitlistOfferMode modeFor(String value) {
        if ("OFFER_NEXT".equals(value)) return new OfferNext();
        if ("RETRY_CURRENT".equals(value)) return new RetryCurrent();
        if ("ROUND_ROBIN".equals(value)) return new RoundRobin();
        if ("RELEASE_TO_POOL".equals(value)) return new ReleaseToPool();
        return null;
    }
    private Map<String, Object> readMap(String value) {
        try { return mapper.readValue(value, mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)); }
        catch (Exception e) { return Map.of(); }
    }
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String opaque(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static final class TimestampNow { static java.sql.Timestamp value() { return java.sql.Timestamp.from(Instant.now()); } }
}
