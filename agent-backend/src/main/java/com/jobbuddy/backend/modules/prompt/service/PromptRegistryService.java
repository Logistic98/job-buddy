package com.jobbuddy.backend.modules.prompt.service;

import java.util.Map;

public interface PromptRegistryService {
    String activeProfile();
    Map<String, Object> frontendWorkbench(String profile);
    Map<String, Object> profileConfig(String profile);
}
