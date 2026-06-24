package com.jobbuddy.backend.common.security;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

public final class AuthenticatedUserContext {
    public static final String USER_ATTRIBUTE = "jobBuddy.authenticatedUser";

    private AuthenticatedUserContext() {
    }

    public static String userId(HttpServletRequest request) {
        Map<String, Object> user = user(request);
        Object userId = user.get("userId");
        if (userId == null || String.valueOf(userId).trim().isEmpty()) {
            throw new IllegalArgumentException("未登录或登录已过期");
        }
        return String.valueOf(userId);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> user(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(USER_ATTRIBUTE);
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        throw new IllegalArgumentException("未登录或登录已过期");
    }
}
