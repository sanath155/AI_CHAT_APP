package com.ai.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiGenerationConfigDto {
    private Integer maxOutputTokens;
    private Double temperature;
}
