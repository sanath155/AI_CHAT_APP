package com.ai.chat.client;

import com.ai.chat.dto.UserContext;
import com.ai.chat.entities.ChatSession;
import reactor.core.publisher.Flux;

public interface LLMClient {

    String getProviderName();

    Flux<String> stream(String prompt, UserContext userContext, ChatSession chatSession);

    void generateTitle(String prompt,ChatSession session);
}
