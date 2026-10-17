package io.stagepass.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitPolicySelectorTest {
    private final RateLimitPolicySelector selector = new RateLimitPolicySelector();

    @Test void assignsDedicatedPoliciesToLoginHoldAndConfirm() {
        assertEquals("login", select("POST", "/api/auth/login"));
        assertEquals("login", select("POST", "/api/auth/register"));
        assertEquals("seat-hold", select("POST", "/api/booking/shows/show-copper/holds"));
        assertEquals("confirm", select("POST", "/api/booking/bookings/abc-123/confirm"));
    }

    @Test void usesDefaultPolicyForOtherRoutesAndMethods() {
        assertEquals("default", select("GET", "/api/catalog/shows"));
        assertEquals("default", select("GET", "/api/booking/bookings"));
        assertEquals("default", select("GET", "/api/booking/shows/show-copper/holds"));
    }

    private String select(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        return selector.select(request);
    }
}
