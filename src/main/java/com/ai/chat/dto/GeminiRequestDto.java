package com.ai.chat.dto;

import com.ai.chat.records.GeminiMessagesRecord;
import com.ai.chat.records.GeminiPartsRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiRequestDto {

    private GeminiPartsRecord systemInstruction;
    private List<GeminiMessagesRecord> contents;
    private GeminiGenerationConfigDto generationConfig;
}
