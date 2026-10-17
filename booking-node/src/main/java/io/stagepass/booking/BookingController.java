package io.stagepass.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping
public class BookingController {
    private final BookingService bookings;
    private final RealtimeService realtime;
    private final String paymentToken;
    public BookingController(BookingService bookings, RealtimeService realtime, @Value("${stagepass.payment.service-token}") String paymentToken) {
        this.bookings = bookings; this.realtime = realtime; this.paymentToken = paymentToken;
    }

    public record HoldRequest(List<String> seatIds) {}
    public record WaitlistRequest(int quantity) {}
    public record WaitlistModeRequest(String mode) {}
    public record ConfirmRequest(String paymentMethodToken) {}
    public record PaymentCallback(UUID paymentId, UUID bookingId, String status) {}

    @PostMapping("/internal/payment-callback")
    public Map<String, String> paymentCallback(@RequestHeader("X-Service-Token") String received,
                                               @RequestBody PaymentCallback callback) {
        if (!MessageDigest.isEqual(paymentToken.getBytes(StandardCharsets.UTF_8), received.getBytes(StandardCharsets.UTF_8)))
            throw new BookingException("UNAUTHORIZED_SERVICE", "Payment callback authentication failed.", HttpStatus.UNAUTHORIZED);
        bookings.receivePaymentCallback(callback.paymentId(), callback.bookingId(), callback.status());
        return Map.of("status", "accepted");
    }

    @GetMapping("/shows/{showId}/seats")
    public List<BookingService.SeatView> seats(@PathVariable String showId) { return bookings.seatsForShow(showId); }

    @PostMapping("/shows/{showId}/presence")
    public RealtimeService.PresenceCount heartbeat(@PathVariable String showId, JwtAuthenticationToken auth) {
        return realtime.heartbeat(showId, auth.getToken().getSubject());
    }

    @GetMapping("/shows/{showId}/presence")
    public RealtimeService.PresenceCount presence(@PathVariable String showId) { return realtime.countPresence(showId); }

    @GetMapping(value = "/shows/{showId}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable String showId) { return realtime.subscribe(showId); }

    @PostMapping("/shows/{showId}/waitlist")
    public ResponseEntity<RealtimeService.WaitlistPosition> joinWaitlist(@PathVariable String showId,
                                                                          @RequestBody WaitlistRequest request,
                                                                          JwtAuthenticationToken auth) {
        if (request.quantity() < 1 || request.quantity() > 8) throw BookingException.badRequest("INVALID_WAITLIST_QUANTITY", "Choose between 1 and 8 seats.");
        if (bookings.seatsForShow(showId).isEmpty()) throw BookingException.notFound("SHOW_NOT_FOUND", "Show was not found.");
        return ResponseEntity.status(201).body(realtime.join(showId, request.quantity(), auth.getToken().getSubject()));
    }

    @GetMapping("/shows/{showId}/waitlist/me")
    public RealtimeService.WaitlistPosition waitlistPosition(@PathVariable String showId,
                                                               JwtAuthenticationToken auth) {
        return realtime.position(showId, auth.getToken().getSubject());
    }

    @PostMapping("/shows/{showId}/waitlist/accept")
    public ResponseEntity<BookingService.HoldResult> acceptWaitlistOffer(@PathVariable String showId,
                                                                           JwtAuthenticationToken auth) {
        String userId = auth.getToken().getSubject();
        RealtimeService.WaitlistOffer offer = realtime.claimOffer(showId, userId);
        if (offer == null) throw BookingException.conflict("WAITLIST_OFFER_NOT_ACTIVE", "There is no active offer for this user.");
        try {
            BookingService.HoldResult hold = bookings.holdFromWaitlistOffer(showId, userId, offer);
            realtime.completeOffer(showId, userId, offer);
            return ResponseEntity.status(201).body(hold);
        } catch (RuntimeException error) {
            realtime.restoreOffer(showId, userId, offer);
            throw error;
        }
    }

    @PutMapping("/admin/shows/{showId}/waitlist-mode")
    public Map<String, String> setWaitlistMode(@PathVariable String showId, @RequestBody WaitlistModeRequest request,
                                                JwtAuthenticationToken auth) {
        if (!"admin".equals(auth.getToken().getClaimAsString("role")))
            throw new BookingException("FORBIDDEN", "Admin role required.", HttpStatus.FORBIDDEN);
        realtime.setMode(showId, request.mode());
        return Map.of("showId", showId, "mode", request.mode());
    }

    @PostMapping("/shows/{showId}/holds")
    public ResponseEntity<BookingService.HoldResult> hold(@PathVariable String showId, @RequestBody HoldRequest request, JwtAuthenticationToken auth) {
        return ResponseEntity.status(201).body(bookings.hold(showId, auth.getToken().getSubject(), request.seatIds()));
    }

    @PostMapping("/bookings/{bookingId}/confirm")
    public ResponseEntity<BookingService.ConfirmOutcome> confirm(
        @PathVariable UUID bookingId, @RequestHeader("Idempotency-Key") String key,
        @RequestBody ConfirmRequest request, JwtAuthenticationToken auth) {
        BookingService.ConfirmOutcome result = bookings.confirm(bookingId, auth.getToken().getSubject(), key, request.paymentMethodToken());
        return ResponseEntity.status(result.httpStatus()).body(result);
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    public Map<String, Object> cancel(@PathVariable UUID bookingId, JwtAuthenticationToken auth) {
        return bookings.cancel(bookingId, auth.getToken().getSubject());
    }

    @PutMapping("/bookings/{bookingId}/extend")
    public Map<String, Object> extend(@PathVariable UUID bookingId, JwtAuthenticationToken auth) {
        return Map.of("bookingId", bookingId, "expiresAt", bookings.extend(bookingId, auth.getToken().getSubject()));
    }

    @GetMapping("/bookings")
    public List<BookingService.BookingView> mine(JwtAuthenticationToken auth) {
        return bookings.listForUser(auth.getToken().getSubject());
    }
}
