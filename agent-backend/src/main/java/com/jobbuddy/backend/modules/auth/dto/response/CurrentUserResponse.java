package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class CurrentUserResponse extends MapBackedDto {
    public CurrentUserResponse() {
    }

    public CurrentUserResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static CurrentUserResponse from(Map<String, Object> fields) {
        return new CurrentUserResponse(fields);
    }
}
