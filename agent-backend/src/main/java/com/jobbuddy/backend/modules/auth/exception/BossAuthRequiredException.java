package com.jobbuddy.backend.modules.auth.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class BossAuthRequiredException extends RuntimeException {
    private final Map<String, Object> authData;

    public BossAuthRequiredException(String message, Map<String, Object> authData) {
        super(message);
        this.authData = authData == null ? Collections.<String, Object>emptyMap() : new LinkedHashMap<String, Object>(authData);
    }

    public Map<String, Object> getAuthData() {
        return authData;
    }
}
