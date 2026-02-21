package com.ai.chat.config;

import com.ai.chat.dto.UserContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers("/login.html",
                                "register.html",
                                "chat.html",
                                "favicon.ico",
                                "/css/**",
                                "/js/**").permitAll()
                        .anyExchange().authenticated()
                )
                .oauth2ResourceServer(oauth -> oauth.jwt(
                        jwtSpec -> jwtSpec.jwkSetUri("http://localhost:8081/auth/.well-known/jwks.json")
                )).addFilterAfter((exchange, chain) ->
                                ReactiveSecurityContextHolder.getContext()
                                        .map(SecurityContext::getAuthentication)
                                        .map(Authentication::getPrincipal)
                                        .cast(org.springframework.security.oauth2.jwt.Jwt.class)
                                        .map(jwt -> {
                                            // 1. Extract values from the JWT
                                            String userId = jwt.getSubject();
                                            String userName = jwt.getClaimAsString("username");
                                            String email = jwt.getClaimAsString("email");

                                            // 2. Create your DTO
                                            return new UserContext(userId, userName, email);
                                        })
                                        .flatMap(userContext ->
                                                // 3. Put it in the Reactor Context "pocket"
                                                chain.filter(exchange)
                                                        .contextWrite(ctx -> ctx.put("USER_DATA", userContext))
                                        )
                                        .switchIfEmpty(chain.filter(exchange)),
                        SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}

