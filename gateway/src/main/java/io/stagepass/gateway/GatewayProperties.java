package io.stagepass.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stagepass")
public class GatewayProperties {
    private String authBaseUrl = "http://localhost:8081";
    private String catalogBaseUrl = "http://localhost:8082";
    private String bookingNodes = "http://localhost:8091,http://localhost:8092,http://localhost:8093";
    private String jwtSecret;
    private String jwtIssuer = "stagepass";
    private boolean trustProxyHeaders = false;
    private String corsAllowedOrigins = "http://localhost:5173";
    private final RateLimits rateLimits = new RateLimits();

    public String getAuthBaseUrl() { return authBaseUrl; }
    public void setAuthBaseUrl(String authBaseUrl) { this.authBaseUrl = authBaseUrl; }
    public String getCatalogBaseUrl() { return catalogBaseUrl; }
    public void setCatalogBaseUrl(String catalogBaseUrl) { this.catalogBaseUrl = catalogBaseUrl; }
    public String getBookingNodes() { return bookingNodes; }
    public void setBookingNodes(String bookingNodes) { this.bookingNodes = bookingNodes; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public String getJwtIssuer() { return jwtIssuer; }
    public void setJwtIssuer(String jwtIssuer) { this.jwtIssuer = jwtIssuer; }
    public boolean isTrustProxyHeaders() { return trustProxyHeaders; }
    public void setTrustProxyHeaders(boolean trustProxyHeaders) { this.trustProxyHeaders = trustProxyHeaders; }
    public String getCorsAllowedOrigins() { return corsAllowedOrigins; }
    public void setCorsAllowedOrigins(String corsAllowedOrigins) { this.corsAllowedOrigins = corsAllowedOrigins; }
    public RateLimits getRateLimits() { return rateLimits; }

    public static class RateLimits {
        private final Map<String, Policy> policies = new LinkedHashMap<>();
        public Map<String, Policy> getPolicies() { return policies; }
    }

    public static class Policy {
        private String strategy = "sliding-window-counter";
        private String identity = "caller";
        private int limit = 60;
        private Duration window = Duration.ofMinutes(1);
        private int capacity = 5;
        private int refillTokens = 5;
        private Duration refillPeriod = Duration.ofMinutes(1);

        public String getStrategy() { return strategy; }
        public void setStrategy(String strategy) { this.strategy = strategy; }
        public String getIdentity() { return identity; }
        public void setIdentity(String identity) { this.identity = identity; }
        public int getLimit() { return limit; }
        public void setLimit(int limit) { this.limit = limit; }
        public Duration getWindow() { return window; }
        public void setWindow(Duration window) { this.window = window; }
        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }
        public int getRefillTokens() { return refillTokens; }
        public void setRefillTokens(int refillTokens) { this.refillTokens = refillTokens; }
        public Duration getRefillPeriod() { return refillPeriod; }
        public void setRefillPeriod(Duration refillPeriod) { this.refillPeriod = refillPeriod; }
    }
}
