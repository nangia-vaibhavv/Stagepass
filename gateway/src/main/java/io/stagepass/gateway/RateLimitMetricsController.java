package io.stagepass.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RateLimitMetricsController {
    private final StringRedisTemplate redis;
    public RateLimitMetricsController(StringRedisTemplate redis) { this.redis = redis; }

    @GetMapping("/admin/rate-limits")
    public Map<String, Object> metrics() {
        Map<Object, Object> values = redis.opsForHash().entries("stagepass:ratelimit:metrics");
        Map<String, Map<String, Long>> byRule = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            String[] parts = key.toString().split(":", 2);
            byRule.computeIfAbsent(parts[0], ignored -> new LinkedHashMap<>()).put(parts[1], Long.parseLong(value.toString()));
        });
        return Map.of("rules", byRule);
    }
}
