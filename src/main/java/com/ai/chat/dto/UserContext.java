package com.ai.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@AllArgsConstructor
@Data
public class UserContext {
    private String userId;
    private String userName;
    private String email;
}
