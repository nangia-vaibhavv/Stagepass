package io.stagepass.booking;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.core.Ordered;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;
import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class SecurityConfig {
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.requestMatchers("/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/shows/*/seats").permitAll()
                .requestMatchers(HttpMethod.GET, "/shows/*/events").permitAll()
                .requestMatchers(HttpMethod.GET, "/shows/*/presence").permitAll()
                .requestMatchers(HttpMethod.POST, "/internal/payment-callback").permitAll().anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${stagepass.jwt.secret}") String secret, @Value("${stagepass.jwt.issuer}") String issuer) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer));
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    RestClient paymentRestClient(@Value("${stagepass.payment.base-url}") String configuredBaseUrl) {
        String baseUrl = configuredBaseUrl;
        String deploymentHost = System.getenv("VERCEL_URL");
        if (deploymentHost != null && !deploymentHost.isBlank())
            baseUrl = "https://" + deploymentHost + "/_internal/payment";
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    FilterRegistrationBean<OncePerRequestFilter> internalServiceRouteFilter(@Value("${stagepass.jwt.secret}") String jwtSecret) {
        String deploymentHost = System.getenv("VERCEL_URL");
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            private static final String PREFIX = "/_internal/booking";

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                if (deploymentHost == null || deploymentHost.isBlank()) {
                    chain.doFilter(request, response);
                    return;
                }
                String requestUri = request.getRequestURI();
                if (!(requestUri.equals(PREFIX) || requestUri.startsWith(PREFIX + "/"))) {
                    chain.doFilter(request, response);
                    return;
                }
                byte[] expected = jwtSecret.getBytes(StandardCharsets.UTF_8);
                byte[] received = request.getHeader("X-StagePass-Internal-Token") == null ? new byte[0]
                    : request.getHeader("X-StagePass-Internal-Token").getBytes(StandardCharsets.UTF_8);
                if (!MessageDigest.isEqual(expected, received)) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                    return;
                }
                String strippedUri = stripPrefix(requestUri);
                String strippedServletPath = stripPrefix(request.getServletPath());
                HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
                    @Override public String getRequestURI() { return strippedUri; }
                    @Override public String getServletPath() { return strippedServletPath; }
                    @Override public StringBuffer getRequestURL() {
                        StringBuffer url = new StringBuffer(super.getRequestURL());
                        int pathIndex = url.lastIndexOf(requestUri);
                        if (pathIndex >= 0) url.replace(pathIndex, pathIndex + requestUri.length(), strippedUri);
                        return url;
                    }
                };
                chain.doFilter(wrapped, response);
            }

            private String stripPrefix(String path) {
                if (path == null || path.isEmpty() || !path.startsWith(PREFIX)) return path;
                String remainder = path.substring(PREFIX.length());
                return remainder.isEmpty() ? "/" : remainder;
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
