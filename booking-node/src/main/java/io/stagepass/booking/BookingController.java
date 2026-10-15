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

@RestController
@RequestMapping
public class BookingController {
    private final BookingService bookings;
    private final String paymentToken;
    public BookingController(BookingService bookings, @Value("${stagepass.payment.service-token}") String paymentToken) {
        this.bookings = bookings; this.paymentToken = paymentToken;
    }

    public record HoldRequest(List<String> seatIds) {}
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
