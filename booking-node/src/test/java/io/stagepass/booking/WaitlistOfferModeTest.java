package io.stagepass.booking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaitlistOfferModeTest {
    private final List<String> queue = List.of("a", "b", "c");

    @Test void offerNextSkipsExpiredMember() { assertEquals("b", new OfferNext().next(queue, "a")); }
    @Test void retryCurrentKeepsCurrentMember() { assertEquals("b", new RetryCurrent().next(queue, "b")); }
    @Test void roundRobinMovesToMemberAfterCurrentAndWraps() {
        assertEquals("c", new RoundRobin().next(queue, "b"));
        assertEquals("a", new RoundRobin().next(queue, "c"));
    }
    @Test void releaseToPoolDoesNotSelectWaitlistMember() { assertNull(new ReleaseToPool().next(queue, "a")); }
}
