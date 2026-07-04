package com.jobbuddy.backend.common.security;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class ApiAuthenticationInterceptor implements HandlerInterceptor {
    private final UserLoginService userLoginService;
    private final JobBuddyProperties properties;
    private final JsonCodec jsonCodec;

    public ApiAuthenticationInterceptor(UserLoginService userLoginService, JobBuddyProperties properties, JsonCodec jsonCodec) {
        this.userLoginService = userLoginService;
        this.properties = properties;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (isPublicRequest(request)) {
            return true;
        }

        if (!properties.getAuth().isEnabled()) {
            request.setAttribute(AuthenticatedUserContext.USER_ATTRIBUTE, localUser());
            return true;
        }

        if (isInternalRequest(request)) {
            request.setAttribute(AuthenticatedUserContext.USER_ATTRIBUTE, internalUser());
            return true;
        }

        String token = extractToken(request);
        AuthenticatedUser user = userLoginService.currentUser(token);
        if (user == null) {
            writeUnauthorized(response);
            return false;
        }

        request.setAttribute(AuthenticatedUserContext.USER_ATTRIBUTE, user);
        return true;
    }

    private boolean isPublicRequest(HttpServletRequest request) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        String path = request.getRequestURI();
        return "/api/auth/login".equals(path)
                || "/api/health".equals(path)
                || path.startsWith("/actuator/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/webjars")
                || "/swagger-ui.html".equals(path)
                || "/doc.html".equals(path)
                || "/favicon.ico".equals(path);
    }

    private boolean isInternalRequest(HttpServletRequest request) {
        String configured = properties.getAuth().getInternalApiToken();
        if (configured == null || configured.trim().isEmpty()) {
            return false;
        }
        String provided = request.getHeader("X-Internal-Api-Token");
        return configured.trim().equals(provided == null ? "" : provided.trim());
    }

    private AuthenticatedUser internalUser() {
        return new AuthenticatedUser(properties.getDefaultUserId(), "internal", "Internal Service", "system");
    }

    private AuthenticatedUser localUser() {
        return new AuthenticatedUser(properties.getDefaultUserId(), "local", "Local User", "local");
    }

    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null) {
            String value = authorization.trim();
            if (value.toLowerCase().startsWith("bearer ")) {
                return value.substring(7).trim();
            }
            return value;
        }
        String queryToken = request.getParameter("access_token");
        return queryToken == null ? "" : queryToken.trim();
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(jsonCodec.toJson(ApiResponse.error(401, "未登录或登录已过期")));
    }
}
