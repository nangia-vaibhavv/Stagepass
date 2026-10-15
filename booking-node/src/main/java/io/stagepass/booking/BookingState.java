package io.stagepass.booking;

public interface BookingState {
    String name();
    default BookingState selectSeats() { throw illegal("select seats"); }
    default BookingState pay() { throw illegal("start payment"); }
    default BookingState paymentSucceeded() { throw illegal("complete payment"); }
    default BookingState paymentFailed() { throw illegal("fail payment"); }
    default BookingState cancel() { throw illegal("cancel"); }
    default BookingState timeout() { throw illegal("expire"); }
    private BookingException illegal(String action) {
        return BookingException.conflict("ILLEGAL_BOOKING_TRANSITION", "Cannot " + action + " while booking is " + name());
    }
}
