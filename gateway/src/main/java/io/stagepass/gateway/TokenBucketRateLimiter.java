package io.stagepass.gateway;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class TokenBucketRateLimiter implements RateLimiter {
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
        local tm = redis.call('TIME')
        local now = tonumber(tm[1]) * 1000 + math.floor(tonumber(tm[2]) / 1000)
        local capacity = tonumber(ARGV[1])
        local refillTokens = tonumber(ARGV[2])
        local refillPeriod = tonumber(ARGV[3])
        local state = redis.call('HMGET', KEYS[1], 'tokens', 'updated')
        local tokens = tonumber(state[1]) or capacity
        local updated = tonumber(state[2]) or now
        if now < updated then now = updated end
        tokens = math.min(capacity, tokens + ((now - updated) * refillTokens / refillPeriod))
        local allowed = 0
        local retry = 0
        if tokens >= 1 then
          tokens = tokens - 1
          allowed = 1
        else
          retry = math.ceil((1 - tokens) * refillPeriod / refillTokens)
        end
        redis.call('HSET', KEYS[1], 'tokens', tokens, 'updated', now)
        redis.call('PEXPIRE', KEYS[1], math.max(refillPeriod * 2, 1000))
        local reset = math.ceil((capacity - tokens) * refillPeriod / refillTokens)
        return {allowed, math.floor(tokens), retry, reset}
        """, List.class);

    private final StringRedisTemplate redis;
    public TokenBucketRateLimiter(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public RateLimitDecision consume(String key, GatewayProperties.Policy policy) {
        long refillPeriod = Math.max(1, policy.getRefillPeriod().toMillis());
        List<?> result = redis.execute(SCRIPT, List.of(key), Integer.toString(policy.getCapacity()),
            Integer.toString(policy.getRefillTokens()), Long.toString(refillPeriod));
        return RateLimitDecisions.from(result, policy.getCapacity());
    }
}
