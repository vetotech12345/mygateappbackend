package com.mygateapp.security;

import com.mygateapp.common.web.IdempotencyFilter;
import com.mygateapp.common.web.JsonErrorWriter;
import com.mygateapp.common.web.RateLimitFilter;
import com.mygateapp.common.web.RateLimiter;
import com.mygateapp.config.AppProperties;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** Unauthenticated auth endpoints. {@code /api/v1/auth/me} is NOT public. */
    public static final Set<String> PUBLIC_AUTH_PATHS = Set.of(
            "/api/v1/auth/request-otp", "/api/v1/auth/verify-otp", "/api/v1/auth/refresh", "/api/v1/auth/logout");
    public static final String SIGNED_FILES = "/api/v1/files/**";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, JwtDecoder jwtDecoder, JwtAuthenticationConverter converter,
                                    RestSecurityHandlers handlers, RateLimiter rateLimiter, AppProperties properties,
                                    JsonErrorWriter errorWriter, StringRedisTemplate redis, ObjectMapper objectMapper) {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless bearer-token API, no cookies
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(h -> h.frameOptions(f -> f.deny()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_AUTH_PATHS.toArray(String[]::new)).permitAll()
                        .requestMatchers(HttpMethod.GET, SIGNED_FILES).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(e -> e.authenticationEntryPoint(handlers).accessDeniedHandler(handlers))
                .addFilterAfter(new RateLimitFilter(rateLimiter, properties.rateLimit(), errorWriter),
                        BearerTokenAuthenticationFilter.class)
                .addFilterAfter(new IdempotencyFilter(redis, objectMapper, errorWriter, properties.idempotency().ttl()),
                        RateLimitFilter.class);
        return http.build();
    }

    /**
     * Ignores the Authorization header on public endpoints, so an expired access token sent with
     * {@code /auth/logout} (or a signed file URL) does not cause a 401.
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        return request -> {
            String path = request.getRequestURI();
            if (PUBLIC_AUTH_PATHS.contains(path) || path.startsWith("/api/v1/files/")) {
                return null;
            }
            return delegate.resolve(request);
        };
    }

    /** Only the configured admin web origins may call the API from a browser. Mobile apps are not affected by CORS. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = properties.cors().allowedOrigins() == null ? List.of() : properties.cors().allowedOrigins();
        config.setAllowedOriginPatterns(origins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept", "X-Society-Id", IdempotencyFilter.HEADER));
        config.setExposedHeaders(List.of("Retry-After", "Idempotent-Replayed"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
