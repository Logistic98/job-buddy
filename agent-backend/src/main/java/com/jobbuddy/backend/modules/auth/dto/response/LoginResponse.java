package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class LoginResponse extends MapBackedDto {
    public LoginResponse() {
    }

    public LoginResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static LoginResponse from(Map<String, Object> fields) {
        return new LoginResponse(fields);
    }
}
