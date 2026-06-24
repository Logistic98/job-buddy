package com.jobbuddy.backend;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.job.controller.JobDetailController;
import com.jobbuddy.backend.modules.job.dto.response.JobDetailResponse;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobDetailControllerTest {

    @Test
    void detailWrapsServiceMapIntoTypedResponse() {
        BossCliService service = mock(BossCliService.class);
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("jd", "资深 Java 工程师");
        when(service.jobDetail(eq("sec-1"), any())).thenReturn(detail);

        JobDetailController controller = new JobDetailController(service);
        ApiResponse<JobDetailResponse> response = controller.detail("sec-1", null);

        assertEquals(200, response.getCode());
        assertEquals("资深 Java 工程师", response.getData().get("jd"));
    }

    @Test
    void detailPropagatesBossAuthRequired() {
        BossCliService service = mock(BossCliService.class);
        when(service.jobDetail(any(), any()))
                .thenThrow(new BossAuthRequiredException("need login", Collections.<String, Object>emptyMap()));

        JobDetailController controller = new JobDetailController(service);
        assertThrows(BossAuthRequiredException.class, () -> controller.detail("sec-1", null));
    }

    @Test
    void detailStripsRedundantErrorPrefix() {
        BossCliService service = mock(BossCliService.class);
        when(service.jobDetail(any(), any()))
                .thenThrow(new RuntimeException("岗位详情获取失败：上游限流"));

        JobDetailController controller = new JobDetailController(service);
        ApiResponse<JobDetailResponse> response = controller.detail("sec-1", null);

        assertEquals(5001, response.getCode());
        assertEquals("上游限流", response.getMessage());
    }
}
