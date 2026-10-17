package io.stagepass.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class RateLimitDecisionsTest {
    @Test void parsesAllowedRedisDecision() {
        RateLimitDecision decision = RateLimitDecisions.from(List.of(1L, 4L, 0L, 12000L), 5);
        assertTrue(decision.allowed());
        assertEquals(4, decision.remaining());
        assertEquals(5, decision.limit());
        assertEquals(0, decision.retryAfterSeconds());
    }

    @Test void roundsRetryTimeUpAndNeverAllowsNegativeRemaining() {
        RateLimitDecision decision = RateLimitDecisions.from(List.of(0L, -1L, 1001L, 4500L), 10);
        assertFalse(decision.allowed());
        assertEquals(0, decision.remaining());
        assertEquals(2, decision.retryAfterSeconds());
    }
}
