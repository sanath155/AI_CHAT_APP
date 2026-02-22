package com.ai.chat.controller;

import com.ai.chat.dto.UserContext;
import com.ai.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/api")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String provider,
                               @RequestParam String message,
                               @RequestParam(required = false) Long sessionId) {


       /* return SecurityUtils.getAuthenticatedUser()
                .flatMapMany(user -> chatService.streamChat(provider, message, user, sessionId));*/
        return Flux.deferContextual(ctx -> {
            UserContext user = ctx.get("USER_DATA");
            return chatService.streamChat(provider, message, user, sessionId);
        });
    }

    @PostMapping("/createSession")
    public Mono<ResponseEntity<?>> createSession() {
        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.get("USER_DATA");
            return Mono.fromCallable(() -> chatService.createNewSession(user.getUserId(), user.getUserName()))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    @GetMapping("/loadSessions")
    public Mono<ResponseEntity<?>> loadSessions() {
        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.get("USER_DATA");
            return Mono.fromCallable(() -> chatService.loadSessions(user.getUserId()))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Mono<ResponseEntity<?>> getMessages(@PathVariable Long sessionId) {
        return Mono.deferContextual(ctx -> {
            // 1. Safe pull: if key is missing, user is null instead of crashing
            UserContext user = ctx.getOrDefault("USER_DATA", null);

            if (user == null) {
                // Log this to your console so you know the filter failed
                System.err.println("USER_DATA missing for session: " + sessionId);
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }

            // 2. Proceed with the blocking call on the correct scheduler
            return Mono.fromCallable(() -> chatService.getMessages(sessionId, user.getUserId()))
                    .map(ResponseEntity::ok)
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    @DeleteMapping("/sessions/{sessionId}/deleteSession")
    public Mono<ResponseEntity<?>> deleteSession(@PathVariable Long sessionId) {
        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.get("USER_DATA");
            return Mono.fromCallable(() -> chatService.deleteSession(user.getUserId(), sessionId))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }
}
