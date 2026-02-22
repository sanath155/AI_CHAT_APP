package com.ai.chat.records;

import lombok.Builder;

import java.util.List;

@Builder
public record GeminiMessagesRecord(String role, List<GeminiTextRecord> parts) {
}
