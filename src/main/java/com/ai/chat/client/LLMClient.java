package com.ai.chat.client;

import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface LLMClient {

    Mono<String> generate(String prompt);

    String getProviderName();

    Flux<String> stream(String prompt, UserContext userContext, ChatSession chatSession);
}
