package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

@Data
public class IdResponse {
    private String id;

    public IdResponse() {
    }

    public IdResponse(String id) {
        this.id = id;
    }
}
