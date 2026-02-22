/*
package com.ai.chat.utils;
import com.ai.chat.dto.UserContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import reactor.core.publisher.Mono;

*/
/**
 * Utility class for reactive security operations in WebFlux.
 *//*

public class SecurityUtils {

    */
/**
     * Extracts the UserContext from the Reactive Security Context.
     * Use this in your Controllers or Services to get the current user.
     *//*

    public static Mono<UserContext> getAuthenticatedUser() {
        return ReactiveSecurityContextHolder.getContext()
                .g
                .map(ctx->ctx.get())
                .filter(auth -> auth != null && auth.isAuthenticated())
                .map(auth -> (UserContext) auth.getPrincipal());
    }

    */
/**
     * Helper to get only the User ID as a Mono.
     *//*

    public static Mono<String> getCurrentUserId() {
        return getAuthenticatedUser()
                .map(UserContext::getUserId);
    }
}
*/
