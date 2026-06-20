package com.jobbuddy.backend.modules.chat.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ChatMessageResponse extends MapBackedDto {
    public ChatMessageResponse() {
    }

    public ChatMessageResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ChatMessageResponse from(Map<String, Object> fields) {
        return new ChatMessageResponse(fields);
    }
}
