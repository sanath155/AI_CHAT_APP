package com.ai.chat.service.impl;

import com.ai.chat.client.LLMClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class LLMFactory {

    private final Map<String, LLMClient> clients;

    public LLMFactory(List<LLMClient> clientList) {
        this.clients = clientList.stream()
                .collect(Collectors.toMap(
                        LLMClient::getProviderName,
                        Function.identity()
                ));
    }

    public LLMClient getClient(String provider) {
        return clients.get(provider.toLowerCase());
    }
}

