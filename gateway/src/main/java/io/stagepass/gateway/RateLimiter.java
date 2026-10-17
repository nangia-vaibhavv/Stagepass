package io.stagepass.gateway;

public interface RateLimiter {
    RateLimitDecision consume(String key, GatewayProperties.Policy policy);
}
