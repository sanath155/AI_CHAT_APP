package com.ai.chat.controller;

import com.ai.chat.dto.UserContext;
import com.ai.chat.dto.UserPromptDto;
import com.ai.chat.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/v1/api")
@Tag(name = "Chat Controller", description = "Manages chat sessions, message retrieval, and AI streaming interactions.")
public class ChatController {

    @Autowired
    private ChatService chatService;

    @Operation(summary = "Stream AI Chat Response", description = "Streams the AI response token-by-token using Server-Sent Events (SSE). Supports both Groq and Gemini.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Stream established successfully",
                    content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE)),
            @ApiResponse(responseCode = "401", description = "Unauthorized - Invalid or missing JWT token"),
            @ApiResponse(responseCode = "500", description = "Internal Server Error - AI Provider failure")
    })
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "Prompt and session details", required = true)
            @RequestBody UserPromptDto userPromptDto) {

        return Flux.deferContextual(ctx -> {
            UserContext user = ctx.get("USER_DATA");
            return chatService.streamChat(userPromptDto.getProvider(), userPromptDto.getPrompt(), user, userPromptDto.getSessionId());
        });
    }

    @Operation(summary = "Create New Session", description = "Initializes a new, empty chat session for the authenticated user.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Session created successfully",
                    content = @Content(schema = @Schema(example = "{\"sessionId\": 12345}"))),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @PostMapping("/createSession")
    public Mono<ResponseEntity<?>> createSession() {
        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.getOrDefault("USER_DATA", null);
            if (user == null) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            return chatService.createNewSession(user.getUserId(), user.getUserName())
                    .map(ResponseEntity::ok);
        });
    }

    @Operation(summary = "Load User Sessions", description = "Retrieves a history of all chat sessions for the user, ordered by most recent.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "List of sessions retrieved successfully"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/loadSessions")
    public Mono<ResponseEntity<?>> loadSessions() {
        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.getOrDefault("USER_DATA", null);
            if (user == null) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            return chatService.loadSessions(user.getUserId())
                    .map(ResponseEntity::ok);
        });
    }

    @Operation(summary = "Get Session Messages", description = "Retrieves the full conversation history (user and AI messages) for a specific session ID.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Messages retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Session not found"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @GetMapping("/sessions/{sessionId}/messages")
    public Mono<ResponseEntity<?>> getMessages(
            @Parameter(description = "ID of the session to fetch", required = true, example = "101")
            @PathVariable Long sessionId) {

        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.getOrDefault("USER_DATA", null);
            if (user == null) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            return chatService.getMessages(sessionId, user.getUserId())
                    .map(ResponseEntity::ok);
        });
    }

    @Operation(summary = "Delete Session", description = "Permanently deletes a chat session and clears its history from Cache and Database.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Session deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Session not found"),
            @ApiResponse(responseCode = "401", description = "Unauthorized")
    })
    @DeleteMapping("/sessions/{sessionId}/deleteSession")
    public Mono<ResponseEntity<?>> deleteSession(
            @Parameter(description = "ID of the session to delete", required = true)
            @PathVariable Long sessionId) {

        return Mono.deferContextual(ctx -> {
            UserContext user = ctx.getOrDefault("USER_DATA", null);
            if (user == null) {
                return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
            }
            return chatService.deleteSession(user.getUserId(), sessionId);
        });
    }
}