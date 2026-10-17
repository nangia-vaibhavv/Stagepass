package io.stagepass.gateway;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/error").permitAll()
                .requestMatchers("/admin/**").hasRole("admin")
                .requestMatchers(HttpMethod.GET, "/api/booking/health").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/booking/shows/*/seats").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/booking/shows/*/events").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/booking/shows/*/presence").permitAll()
                .requestMatchers("/api/booking/**").authenticated()
                .anyRequest().permitAll())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
            .build();
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<GrantedAuthority> authorities = new ArrayList<>();
            String role = jwt.getClaimAsString("role");
            if (role != null && !role.isBlank()) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            return authorities;
        });
        return converter;
    }

    @Bean
    JwtDecoder jwtDecoder(GatewayProperties properties) {
        var key = new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.getJwtIssuer()));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
