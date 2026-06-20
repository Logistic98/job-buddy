package com.jobbuddy.backend.modules.auth.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class BossLoginCancelResponse extends MapBackedDto {
    public BossLoginCancelResponse() {
    }

    public BossLoginCancelResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static BossLoginCancelResponse from(Map<String, Object> fields) {
        return new BossLoginCancelResponse(fields);
    }
}
