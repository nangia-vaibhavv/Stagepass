package io.stagepass.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class BookingStatesTest {
    @Test
    void successfulPaymentMovesHeldBookingThroughPendingToConfirmed() {
        BookingState held = BookingStates.browsing().selectSeats();
        BookingState pending = held.pay();
        BookingState confirmed = pending.paymentSucceeded();
        assertEquals("HELD", held.name());
        assertEquals("PAYMENT_PENDING", pending.name());
        assertEquals("CONFIRMED", confirmed.name());
    }

    @Test
    void declinedPaymentCanBeRetriedOrCancelled() {
        BookingState failed = BookingStates.from("HELD").pay().paymentFailed();
        assertEquals("PAYMENT_FAILED", failed.name());
        assertEquals("PAYMENT_PENDING", failed.pay().name());
        assertEquals("CANCELLED", failed.cancel().name());
    }

    @Test
    void terminalBookingsRejectIllegalActions() {
        BookingState confirmed = BookingStates.from("HELD").pay().paymentSucceeded();
        assertThrows(BookingException.class, confirmed::cancel);
        assertEquals("EXPIRED", BookingStates.from("HELD").timeout().name());
    }

    @Test
    void illegalActionsAreRejectedInEveryPersistedState() {
        assertThrows(BookingException.class, () -> BookingStates.browsing().pay());
        assertThrows(BookingException.class, () -> BookingStates.from("HELD").paymentSucceeded());
        assertThrows(BookingException.class, () -> BookingStates.from("PAYMENT_PENDING").pay());
        assertThrows(BookingException.class, () -> BookingStates.from("PAYMENT_FAILED").paymentSucceeded());
        assertThrows(BookingException.class, () -> BookingStates.from("CONFIRMED").timeout());
        assertThrows(BookingException.class, () -> BookingStates.from("EXPIRED").cancel());
        assertThrows(BookingException.class, () -> BookingStates.from("CANCELLED").selectSeats());
    }

    @Test
    void pendingPaymentCanExpireOrBeCancelled() {
        BookingState pending = BookingStates.from("HELD").pay();
        assertEquals("EXPIRED", pending.timeout().name());
        assertEquals("CANCELLED", pending.cancel().name());
    }
}
