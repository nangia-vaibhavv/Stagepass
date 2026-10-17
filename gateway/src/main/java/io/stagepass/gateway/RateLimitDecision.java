package io.stagepass.gateway;

public record RateLimitDecision(boolean allowed, long remaining, long retryAfterSeconds, int limit, long resetEpochSeconds) {}
