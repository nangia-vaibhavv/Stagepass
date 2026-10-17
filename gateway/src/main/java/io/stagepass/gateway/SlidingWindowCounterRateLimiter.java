package io.stagepass.gateway;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class SlidingWindowCounterRateLimiter implements RateLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
        local tm = redis.call('TIME')
        local now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
        local windowMs = tonumber(ARGV[1])
        local limit = tonumber(ARGV[2])
        local window = math.floor(now / windowMs)
        local elapsed = now - window * windowMs
        local state = redis.call('HMGET', KEYS[1], 'window', 'current', 'previous')
        local oldWindow = tonumber(state[1])
        local current = tonumber(state[2]) or 0
        local previous = tonumber(state[3]) or 0
        if oldWindow ~= window then
          if oldWindow and oldWindow == window - 1 then previous = current else previous = 0 end
          current = 0
          oldWindow = window
        end
        local weightedPrevious = previous * (windowMs - elapsed) / windowMs
        local used = current + weightedPrevious
        local allowed = 0
        local retry = 0
        if used + 1 <= limit then
          current = current + 1
          allowed = 1
        else
          if previous > 0 and current < limit then
            local decayMs = windowMs * (used - (limit - 1)) / previous
            if decayMs <= windowMs - elapsed then
              retry = math.max(1, math.ceil(decayMs))
            else
              retry = windowMs - elapsed
              if current >= limit then retry = retry + math.ceil(windowMs * (current - (limit - 1)) / current) end
            end
          elseif current >= limit then
            local afterBoundary = 1
            if current > limit - 1 then afterBoundary = math.ceil(windowMs * (1 - (limit - 1) / current)) end
            retry = windowMs - elapsed + afterBoundary
          else
            retry = windowMs - elapsed + 1
          end
        end
        redis.call('HSET', KEYS[1], 'window', oldWindow, 'current', current, 'previous', previous)
        redis.call('PEXPIRE', KEYS[1], windowMs * 2 + 1000)
        local remaining = math.max(0, math.floor(limit - current - weightedPrevious))
        return {allowed, remaining, retry, windowMs - elapsed}
        """, List.class);

    private final StringRedisTemplate redis;
    public SlidingWindowCounterRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public RateLimitDecision consume(String key, GatewayProperties.Policy policy) {
        long window = Math.max(1, policy.getWindow().toMillis());
        List<?> result = redis.execute(SCRIPT, List.of(key), Long.toString(window), Integer.toString(policy.getLimit()));
        return RateLimitDecisions.from(result, policy.getLimit());
    }
}
