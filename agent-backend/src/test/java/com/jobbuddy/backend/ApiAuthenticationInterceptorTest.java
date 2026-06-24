package com.jobbuddy.backend;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_backend_auth_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:/schema-test.sql",
        "spring.flyway.enabled=false",
        "job-buddy.auth.enabled=true"
})
@AutoConfigureMockMvc
class ApiAuthenticationInterceptorTest {
    private static final String TOKEN = "auth-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM user_login_session");
        jdbcTemplate.update("DELETE FROM app_user");
        jdbcTemplate.update(
                "INSERT INTO app_user(user_id, username, password_hash, display_name, role, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                "user-auth-1",
                "auth_user",
                "$2a$10$012345678901234567890uT08Y9MtBz6Xklj1m0K4E2NQ0BzTAb1K",
                "Auth User",
                "user",
                Boolean.TRUE,
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())
        );
        jdbcTemplate.update(
                "INSERT INTO user_login_session(token, user_id, expires_at, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?)",
                TOKEN,
                "user-auth-1",
                Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)),
                Timestamp.from(Instant.now()),
                Timestamp.from(Instant.now())
        );
    }

    @Test
    void protectedApiRejectsAnonymousRequests() throws Exception {
        mockMvc.perform(get("/api/settings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void protectedApiAcceptsValidBearerToken() throws Exception {
        mockMvc.perform(get("/api/settings").header("Authorization", "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void publicHealthEndpointStaysAnonymous() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
