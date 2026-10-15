package io.stagepass.booking;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis) { this.jdbc = jdbc; this.redis = redis; }
    @GetMapping("/health")
    public Map<String, String> health() {
        jdbc.queryForObject("SELECT 1", Integer.class);
        try (var connection = redis.getConnectionFactory().getConnection()) { connection.ping(); }
        return Map.of("status", "ok", "service", "booking", "nodeId", System.getenv().getOrDefault("NODE_ID", "booking-node"));
    }
}
