package io.stagepass.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitIdentityTest {
    @Test void ignoresForwardedHeadersUnlessProxyTrustIsEnabled() {
        MockHttpServletRequest request = request("10.0.0.8", "203.0.113.40");

        assertEquals("10.0.0.8", RateLimitInterceptor.sourceIp(request, false));
    }

    @Test void usesTheRightmostValidForwardedAddressWhenProxyTrustIsEnabled() {
        MockHttpServletRequest request = request("172.20.0.4", "198.51.100.22, 203.0.113.40");

        assertEquals("203.0.113.40", RateLimitInterceptor.sourceIp(request, true));
    }

    @Test void fallsBackToSocketAddressForMalformedForwardedAddress() {
        MockHttpServletRequest request = request("172.20.0.4", "attacker.example");

        assertEquals("172.20.0.4", RateLimitInterceptor.sourceIp(request, true));
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        request.addHeader("X-Forwarded-For", forwardedFor);
        return request;
    }
}
