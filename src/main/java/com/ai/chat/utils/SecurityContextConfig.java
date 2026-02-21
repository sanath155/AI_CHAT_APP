/*
package com.ai.chat.utils;

import com.ai.chat.dto.UserContext;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
public class SecurityContextConfig {

    public static Mono<UserContext> getAuthenticatedUserId() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .map(auth -> {
                    Map<String, Object> details = (Map<String, Object>) auth.getDetails();
                    return new UserContext(
                            (String) details.get("userId"),
                            (String) details.get("name")
                    );
                });
    }
}
*/
