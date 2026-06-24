package com.jobbuddy.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_backend_trace_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false",
        "agent.services.runtime-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class AgentFlowTraceContractTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void synchronousAskShouldExposeRuntimeProxyTraceOnly() throws Exception {
        mockMvc.perform(post("/api/chat/ask")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"帮我设计一个复杂问答 Agent 工作流\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.trace.length()", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.trace[0].nodeId").value("runtime_proxy"));
    }
}
