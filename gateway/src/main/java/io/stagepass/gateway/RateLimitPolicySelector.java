package io.stagepass.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class RateLimitPolicySelector {
    public String select(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equals(request.getMethod()) && (path.equals("/api/auth/login") || path.equals("/api/auth/register") ||
            path.equals("/api/auth/auth/login") || path.equals("/api/auth/auth/register"))) return "login";
        if ("POST".equals(request.getMethod()) && path.matches("/api/booking/shows/[^/]+/holds")) return "seat-hold";
        if ("POST".equals(request.getMethod()) && path.matches("/api/booking/bookings/[^/]+/confirm")) return "confirm";
        return "default";
    }
}
