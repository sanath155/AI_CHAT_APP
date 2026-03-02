package com.ai.chat.service.impl;

import com.ai.chat.cache.SessionHistory;
import com.ai.chat.client.LLMClient;
import com.ai.chat.dto.ChatMessageDto;
import com.ai.chat.dto.ChatSessionDto;
import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatSession;
import com.ai.chat.repositories.ChatMessageRepository;
import com.ai.chat.repositories.ChatSessionRepository;
import com.ai.chat.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class ChatServiceImpl implements ChatService {

    private final LLMFactory factory;

    @Autowired
    ChatSessionRepository chatSessionRepository;

    @Autowired
    ChatMessageRepository chatMessageRepository;

    @Autowired
    SessionHistory sessionHistory;

    public ChatServiceImpl(LLMFactory factory) {
        this.factory = factory;
    }

    @Override
    public Flux<String> streamChat(String provider, String message, UserContext userContext, Long sessionId) {
        Mono<Long> sessionMono = (sessionId == null)
                ? Mono.fromCallable(() -> {
            ChatSession newSession = ChatSession.builder()
                    .userId(userContext.getUserId())
                    .userName(userContext.getUserName())
                    .build();
            return chatSessionRepository.save(newSession).getSessionId();
        }).subscribeOn(Schedulers.boundedElastic())
                : Mono.just(sessionId);

        return sessionMono.flatMapMany(finalSessionId -> {
            LLMClient client = factory.getClient(provider);
            return client.stream(message, userContext, finalSessionId);
        });
    }

    @Override
    public Mono<ResponseEntity<Map<String, Long>>> createNewSession(String userId, String userName) {
        return Mono.fromCallable(() -> chatSessionRepository.save(
                        ChatSession.builder().userId(userId).userName(userName).build()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(session -> {
                    ChatSessionDto dto = new ChatSessionDto(
                            session.getSessionId(), session.getCreatedDate(), null);
                    return sessionHistory.updateOrAddSession(userId, dto)
                            .thenReturn(ResponseEntity.ok(Map.of("sessionId", session.getSessionId())));
                });
    }

    @Override
    public Mono<ResponseEntity<String>> deleteSession(String userId, Long sessionId) {
        return Mono.fromCallable(() -> chatSessionRepository.findTopByUserIdAndSessionId(userId, sessionId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(sessionOpt -> {
                    if (sessionOpt.isPresent()) {
                        Mono<Void> dbDelete = Mono.fromRunnable(() -> chatSessionRepository.delete(sessionOpt.get()))
                                .subscribeOn(Schedulers.boundedElastic()).then();

                        Mono<Void> cacheEvict = sessionHistory.evictCache(userId, sessionId);

                        return Mono.when(dbDelete, cacheEvict)
                                .thenReturn(ResponseEntity.ok("Session deleted successfully"));
                    }
                    return Mono.just(ResponseEntity.noContent().build());
                });
    }

    @Override
    public Mono<List<ChatSessionDto>> loadSessions(String userId) {
        return sessionHistory.getAllSessionHistory(userId)
                .onErrorResume(e -> {
                    System.err.println("Redis failed for loadSessions: " + e.getMessage());
                    return Mono.just(Collections.emptyList());
                })
                .flatMap(cacheSession -> {
                    if (!cacheSession.isEmpty()) {
                        return Mono.just(cacheSession);
                    }
                    return Mono.fromCallable(() -> chatSessionRepository
                                    .findByUserIdOrderByCreatedDateDesc(userId)
                                    .stream()
                                    .map(s -> new ChatSessionDto(s.getSessionId(), s.getCreatedDate(), s.getTitle()))
                                    .toList())
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(dbList -> {
                                if (dbList.isEmpty()) return Mono.just(dbList);
                                sessionHistory.loadFreshChatSessionCache(userId, dbList).subscribe();
                                return Mono.just(dbList);
                            });
                });
    }

    @Override
    public Mono<List<ChatMessageDto>> getMessages(Long sessionId, String userId) {
        return sessionHistory.getAllMessageHistory(userId, sessionId)
                .onErrorResume(e -> Mono.just(Collections.emptyList()))
                .flatMap(cacheList -> {
                    if (!cacheList.isEmpty()) return Mono.just(cacheList);

                    return Mono.fromCallable(() -> chatMessageRepository
                                    .findBySession_SessionIdOrderByCreatedDateAsc(sessionId)
                                    .stream()
                                    .map(msg -> new ChatMessageDto(msg.getRole(), msg.getContent(), msg.getCreatedDate()))
                                    .toList())
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(dbList -> {
                                if (dbList.isEmpty()) return Mono.just(dbList);
                                sessionHistory.loadFreshChatMessageCache(sessionId, userId, dbList).subscribe();
                                return Mono.just(dbList);
                            });
                });
    }
}