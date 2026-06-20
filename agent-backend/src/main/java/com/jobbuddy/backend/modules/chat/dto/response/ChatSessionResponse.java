package com.jobbuddy.backend.modules.chat.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ChatSessionResponse extends MapBackedDto {
    public ChatSessionResponse() {
    }

    public ChatSessionResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ChatSessionResponse from(Map<String, Object> fields) {
        return new ChatSessionResponse(fields);
    }
}
