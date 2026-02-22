package com.ai.chat.records;

import com.ai.chat.dto.GeminiGenerationConfigDto;
import lombok.Builder;

import java.util.List;

@Builder
public record GeminiTitleRequestRecord(List<GeminiPartsRecord> contents , GeminiGenerationConfigDto generationConfig) {
}
