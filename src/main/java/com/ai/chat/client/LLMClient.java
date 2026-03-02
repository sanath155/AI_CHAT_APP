package com.ai.chat.client;

import com.ai.chat.dto.ChatSessionDto;
import com.ai.chat.dto.UserContext;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LLMClient {

    String getProviderName();

    Flux<String> stream(String prompt, UserContext userContext, Long sessionId);

    Mono<ChatSessionDto> generateTitle(String userId, String prompt, ChatSessionDto dto);
}
