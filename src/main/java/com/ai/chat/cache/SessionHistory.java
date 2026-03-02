package com.ai.chat.cache;

import com.ai.chat.dto.ChatMessageDto;
import com.ai.chat.dto.ChatSessionDto;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class SessionHistory {

    // Using instance variables is better for testing/Spring context than static
    private final ReactiveRedisTemplate<String, ChatMessageDto> messageTemplate;
    private final ReactiveRedisTemplate<String, ChatSessionDto> sessionTemplate;

    private static final int MAX_MESSAGES = 12;

    public SessionHistory(@Qualifier("chat_message_history_cache") ReactiveRedisTemplate<String, ChatMessageDto> messageTemplate,
                          @Qualifier("chat_session_history_cache") ReactiveRedisTemplate<String, ChatSessionDto> sessionTemplate) {
        this.messageTemplate = messageTemplate;
        this.sessionTemplate = sessionTemplate;
    }

    private String keyMessage(String userId, Long sessionId) {
        return "MESSAGES:" + userId + ":" + sessionId;
    }

    private String keySession(String userId) {
        return "SESSIONS:" + userId;
    }

    private Retry retryStrategy() {
        return Retry.backoff(3, Duration.ofMillis(200))
                .filter(throwable -> throwable instanceof java.net.SocketException ||
                        throwable instanceof io.lettuce.core.RedisCommandTimeoutException);
    }

    public Mono<Long> loadFreshChatMessageCache(Long sessionId, String userId, List<ChatMessageDto> list) {
        String key = keyMessage(userId, sessionId);
        return messageTemplate.delete(key)
                .then(messageTemplate.opsForList().rightPushAll(key, list))
                .flatMap(count -> messageTemplate.expire(key, Duration.ofDays(1)).thenReturn(count))
                .retryWhen(retryStrategy());
    }

    public Mono<List<ChatMessageDto>> getAllMessageHistory(String userId, Long sessionId) {
        String key = keyMessage(userId, sessionId);
        return messageTemplate.opsForList()
                .range(key, 0, -1)
                .collectList()
                .retryWhen(retryStrategy());
    }

    public Mono<Long> addMessageInCache(String userId, Long sessionId, String role, String content) {
        String key = keyMessage(userId, sessionId);
        return messageTemplate.opsForList()
                .rightPush(key, new ChatMessageDto(role, content, Timestamp.from(Instant.now())))
                .retryWhen(retryStrategy());
    }

    public Mono<List<ChatMessageDto>> getLastLimitChat(String userId, Long sessionId) {
        String key = keyMessage(userId, sessionId);
        return messageTemplate.opsForList()
                .range(key, -MAX_MESSAGES, -1)
                .collectList()
                .retryWhen(retryStrategy());
    }

    public Mono<Void> evictCache(String userId, Long sessionId) {
        String keyMessage = keyMessage(userId, sessionId);
        String keySession = keySession(userId);
        return messageTemplate.delete(keyMessage)
                .then(sessionTemplate.opsForHash().remove(keySession, String.valueOf(sessionId)))
                .retryWhen(retryStrategy())
                .then();
    }

    public Mono<List<ChatSessionDto>> getAllSessionHistory(String userId) {
        String key = keySession(userId);
        // Note: values() can be slow on large hashes.
        // Ideally, this should be paginated or use HSCAN, but kept as-is per requirements.
        return sessionTemplate.opsForHash()
                .values(key)
                .cast(ChatSessionDto.class)
                .retryWhen(retryStrategy())
                .collectList();
    }

    public Mono<Boolean> loadFreshChatSessionCache(String userId, List<ChatSessionDto> list) {
        String key = keySession(userId);
        Map<String, ChatSessionDto> sessionMap = list.stream()
                .collect(Collectors.toMap(s -> String.valueOf(s.getSessionId()), s -> s));

        return sessionTemplate.delete(key)
                .then(sessionTemplate.opsForHash().putAll(key, sessionMap))
                .retryWhen(retryStrategy())
                .flatMap(success -> sessionTemplate.expire(key, Duration.ofDays(1)).thenReturn(success));
    }

    public Mono<ChatSessionDto> getSession(String userId, String sessionId) {
        String key = keySession(userId);
        return sessionTemplate.opsForHash()
                .get(key, sessionId)
                .cast(ChatSessionDto.class)
                .retryWhen(retryStrategy());
    }

    public Mono<Boolean> updateOrAddSession(String userId, ChatSessionDto dto) {
        String key = keySession(userId);
        String field = String.valueOf(dto.getSessionId());
        return sessionTemplate.opsForHash()
                .put(key, field, dto)
                .retryWhen(retryStrategy());
    }
}