package com.jobbuddy.backend.modules.prompt.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class FrontendPromptResponse extends MapBackedDto {
    public FrontendPromptResponse() {
    }

    public FrontendPromptResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static FrontendPromptResponse from(Map<String, Object> fields) {
        return new FrontendPromptResponse(fields);
    }
}
