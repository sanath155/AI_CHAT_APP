package com.ai.chat.records;

import lombok.Builder;

import java.util.List;

@Builder
public record GeminiPartsRecord(List<GeminiTextRecord> parts) {
}
