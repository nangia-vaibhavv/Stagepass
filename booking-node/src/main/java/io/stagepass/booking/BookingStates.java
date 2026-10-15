package io.stagepass.booking;

import java.util.Map;

public final class BookingStates {
    private BookingStates() {}
    private record BrowsingState() implements BookingState {
        public String name() { return "BROWSING"; }
        public BookingState selectSeats() { return HELD; }
    }
    private record HeldState() implements BookingState {
        public String name() { return "HELD"; }
        public BookingState pay() { return PAYMENT_PENDING; }
        public BookingState cancel() { return CANCELLED; }
        public BookingState timeout() { return EXPIRED; }
    }
    private record PaymentPendingState() implements BookingState {
        public String name() { return "PAYMENT_PENDING"; }
        public BookingState paymentSucceeded() { return CONFIRMED; }
        public BookingState paymentFailed() { return PAYMENT_FAILED; }
        public BookingState cancel() { return CANCELLED; }
        public BookingState timeout() { return EXPIRED; }
    }
    private record PaymentFailedState() implements BookingState {
        public String name() { return "PAYMENT_FAILED"; }
        public BookingState pay() { return PAYMENT_PENDING; }
        public BookingState cancel() { return CANCELLED; }
        public BookingState timeout() { return EXPIRED; }
    }
    private record ConfirmedState() implements BookingState { public String name() { return "CONFIRMED"; } }
    private record ExpiredState() implements BookingState { public String name() { return "EXPIRED"; } }
    private record CancelledState() implements BookingState { public String name() { return "CANCELLED"; } }

    private static final BookingState BROWSING = new BrowsingState();
    private static final BookingState HELD = new HeldState();
    private static final BookingState PAYMENT_PENDING = new PaymentPendingState();
    private static final BookingState PAYMENT_FAILED = new PaymentFailedState();
    private static final BookingState CONFIRMED = new ConfirmedState();
    private static final BookingState EXPIRED = new ExpiredState();
    private static final BookingState CANCELLED = new CancelledState();
    private static final Map<String, BookingState> STATES = Map.of(
        "BROWSING", BROWSING, "HELD", HELD, "PAYMENT_PENDING", PAYMENT_PENDING,
        "PAYMENT_FAILED", PAYMENT_FAILED, "CONFIRMED", CONFIRMED, "EXPIRED", EXPIRED, "CANCELLED", CANCELLED
    );
    public static BookingState browsing() { return BROWSING; }
    public static BookingState from(String name) {
        BookingState result = STATES.get(name);
        if (result == null) throw new IllegalArgumentException("Unknown booking state: " + name);
        return result;
    }
}
