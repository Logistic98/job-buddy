package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

@Data
public class BooleanResultResponse {
    private boolean ok;

    public BooleanResultResponse() {
    }

    public BooleanResultResponse(boolean ok) {
        this.ok = ok;
    }
}
