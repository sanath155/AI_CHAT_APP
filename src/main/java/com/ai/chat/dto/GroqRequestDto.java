package com.ai.chat.dto;

import com.ai.chat.records.GroqMessagesRecord;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GroqRequestDto {
    private String model;
    private boolean stream;
    private List<GroqMessagesRecord> messages;
    private Double temperature;
    private Double top_p;

}
