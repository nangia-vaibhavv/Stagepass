package io.stagepass.gateway;

import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayHealthController {
    private final StringRedisTemplate redis;
    public GatewayHealthController(StringRedisTemplate redis) { this.redis = redis; }
    @GetMapping("/health")
    public Map<String, String> health() {
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.ping(); }
        return Map.of("status", "ok", "service", "gateway");
    }
}
