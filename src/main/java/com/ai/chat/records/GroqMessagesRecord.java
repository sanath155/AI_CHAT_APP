package com.ai.chat.records;

import lombok.Builder;

@Builder
public record GroqMessagesRecord(String role, String content) {
}
