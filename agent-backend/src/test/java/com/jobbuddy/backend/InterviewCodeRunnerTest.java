package com.jobbuddy.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewCodeRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InterviewCodeRunnerTest {
    @Test
    void shouldDelegateCodeExecutionToAgentSandbox() {
        ObjectMapper objectMapper = new ObjectMapper();
        RestTemplate restTemplate = mock(RestTemplate.class);
        AgentServiceProperties properties = new AgentServiceProperties();
        properties.setSandboxUrl("http://sandbox.local:8000/");

        Map<String, Object> sandboxResponse = new LinkedHashMap<String, Object>();
        sandboxResponse.put("ok", Boolean.TRUE);
        sandboxResponse.put("returncode", Integer.valueOf(0));
        sandboxResponse.put("stdout", "{\"passed\":true,\"rows\":[{\"name\":\"示例\",\"passed\":true}]}\n");
        sandboxResponse.put("stderr", "");
        when(restTemplate.postForObject(eq("http://sandbox.local:8000/v1/code-file"), any(Map.class), eq(Map.class)))
                .thenReturn(sandboxResponse);

        InterviewCodeRunner runner = new InterviewCodeRunner(objectMapper, restTemplate, properties, new ServiceResilience(properties));
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("language", "python");
        payload.put("source", "def solution(nums):\n    return sum(nums)\n");
        payload.put("functionName", "solution");
        Map<String, Object> test = new LinkedHashMap<String, Object>();
        test.put("name", "示例");
        test.put("args", Arrays.<Object>asList(Arrays.asList(1, 2, 3)));
        test.put("expected", Integer.valueOf(6));
        payload.put("tests", Arrays.asList(test));

        Map<String, Object> result = runner.run(payload);

        assertEquals(Boolean.TRUE, result.get("passed"));
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(restTemplate).postForObject(eq("http://sandbox.local:8000/v1/code-file"), bodyCaptor.capture(), eq(Map.class));
        Map body = bodyCaptor.getValue();
        assertEquals(".py", body.get("suffix"));
        assertEquals("python3", body.get("interpreter"));
        assertTrue(String.valueOf(body.get("code")).contains("CODE_B64"));
        assertTrue(body.containsKey("policy"));
        assertTrue(body.containsKey("options"));
    }
}
