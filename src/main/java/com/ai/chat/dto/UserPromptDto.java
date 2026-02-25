package com.ai.chat.dto;

import lombok.Data;

@Data
public class UserPromptDto {
    private String prompt;
    private Long sessionId;
    private String provider;
}
