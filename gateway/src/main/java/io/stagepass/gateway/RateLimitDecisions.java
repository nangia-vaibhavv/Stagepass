package io.stagepass.gateway;

import java.time.Instant;
import java.util.List;

final class RateLimitDecisions {
    private RateLimitDecisions() {}

    static RateLimitDecision from(List<?> result, int limit) {
        if (result == null || result.size() < 4) throw new IllegalStateException("Redis returned an invalid rate-limit decision");
        boolean allowed = number(result.get(0)) == 1;
        long remaining = Math.max(0, number(result.get(1)));
        long retryMs = Math.max(0, number(result.get(2)));
        long resetMs = Math.max(0, number(result.get(3)));
        return new RateLimitDecision(allowed, remaining, (retryMs + 999) / 1000, limit,
            Instant.now().getEpochSecond() + (resetMs + 999) / 1000);
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return Long.parseLong(value.toString());
    }
}
