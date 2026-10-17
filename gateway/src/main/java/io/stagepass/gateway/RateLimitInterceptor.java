package io.stagepass.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private static final String METRICS_KEY = "stagepass:ratelimit:metrics";
    private final GatewayProperties properties;
    private final RateLimitPolicySelector selector;
    private final TokenBucketRateLimiter tokenBucket;
    private final SlidingWindowCounterRateLimiter slidingWindow;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RateLimitInterceptor(GatewayProperties properties, RateLimitPolicySelector selector,
                                TokenBucketRateLimiter tokenBucket, SlidingWindowCounterRateLimiter slidingWindow,
                                StringRedisTemplate redis, ObjectMapper mapper) {
        this.properties = properties; this.selector = selector; this.tokenBucket = tokenBucket;
        this.slidingWindow = slidingWindow; this.redis = redis; this.mapper = mapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String name = selector.select(request);
        GatewayProperties.Policy policy = properties.getRateLimits().getPolicies().get(name);
        if (policy == null) throw new IllegalStateException("Missing rate-limit policy: " + name);

        String identity = identity(request, policy.getIdentity());
        String key = "stagepass:ratelimit:" + name + ":" + digest(identity);
        RateLimiter limiter = "token-bucket".equals(policy.getStrategy()) ? tokenBucket : slidingWindow;
        RateLimitDecision decision = limiter.consume(key, policy);
        redis.opsForHash().increment(METRICS_KEY, name + (decision.allowed() ? ":allowed" : ":blocked"), 1);

        response.setHeader("X-RateLimit-Rule", name);
        response.setHeader("X-RateLimit-Limit", Integer.toString(decision.limit()));
        response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        response.setHeader("X-RateLimit-Reset", Long.toString(decision.resetEpochSeconds()));
        if (decision.allowed()) return true;

        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", Long.toString(Math.max(1, decision.retryAfterSeconds())));
        mapper.writeValue(response.getOutputStream(), Map.of("code", "RATE_LIMITED", "rule", name,
            "retryAfterSeconds", Math.max(1, decision.retryAfterSeconds())));
        return false;
    }

    private String identity(HttpServletRequest request, String dimension) {
        if (!"ip".equals(dimension)) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated())
                return "user:" + jwt.getToken().getSubject();
        }
        // Never use X-Forwarded-For or other client-supplied identity headers here.
        return "ip:" + request.getRemoteAddr();
    }

    private String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception error) { throw new IllegalStateException("SHA-256 is unavailable", error); }
    }
}
