package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.system.mapper.SystemSettingsMapper;
import com.jobbuddy.backend.modules.system.service.impl.SystemSettingsServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemSettingsServiceSecurityTest {
    private final String originalUserHome = System.getProperty("user.home");

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void saveSettingsRejectsNonLoopbackServiceUrls() {
        System.setProperty("user.home", tempDir.toString());
        SystemSettingsServiceImpl service = newService();

        assertThrows(IllegalArgumentException.class, () -> service.saveSettings(settingsWithRuntimeUrl("http://169.254.169.254/latest/meta-data")));
    }

    @Test
    void saveSettingsAllowsLoopbackServiceUrls() {
        System.setProperty("user.home", tempDir.toString());
        SystemSettingsServiceImpl service = newService();

        assertDoesNotThrow(() -> service.saveSettings(settingsWithRuntimeUrl("http://127.0.0.1:8010")));
    }

    private SystemSettingsServiceImpl newService() {
        SystemSettingsMapper mapper = mock(SystemSettingsMapper.class);
        when(mapper.listBlacklistItems()).thenReturn(Collections.<Map<String, Object>>emptyList());
        AgentServiceProperties agentProperties = new AgentServiceProperties();
        agentProperties.setRuntimeUrl("http://127.0.0.1:8010");
        return new SystemSettingsServiceImpl(agentProperties, new JobBuddyProperties(), mapper);
    }

    private Map<String, Object> settingsWithRuntimeUrl(String url) {
        Map<String, Object> services = new LinkedHashMap<String, Object>();
        services.put("runtimeUrl", url);
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("services", services);
        return root;
    }
}
