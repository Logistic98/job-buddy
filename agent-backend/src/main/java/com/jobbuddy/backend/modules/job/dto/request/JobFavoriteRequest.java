package com.jobbuddy.backend.modules.job.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JobFavoriteRequest extends MapBackedDto {
    public JobFavoriteRequest() {
    }

    public JobFavoriteRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static JobFavoriteRequest from(Map<String, Object> fields) {
        return new JobFavoriteRequest(fields);
    }
}
