package com.jobbuddy.backend.modules.prompt.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class ProfileContextResponse extends MapBackedDto {
    public ProfileContextResponse() {
    }

    public ProfileContextResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static ProfileContextResponse from(Map<String, Object> fields) {
        return new ProfileContextResponse(fields);
    }
}
