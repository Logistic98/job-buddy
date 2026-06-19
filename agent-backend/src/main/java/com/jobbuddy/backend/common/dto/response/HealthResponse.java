package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

@Data
public class HealthResponse {
    private String status;
    private String service;

    public HealthResponse() {
    }

    public HealthResponse(String status, String service) {
        this.status = status;
        this.service = service;
    }
}
