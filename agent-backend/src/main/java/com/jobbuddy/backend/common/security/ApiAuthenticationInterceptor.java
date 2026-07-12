package com.jobbuddy.backend.common.security;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.common.web.MdcContextFilter;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiAuthenticationInterceptor implements HandlerInterceptor {
  private final UserLoginService userLoginService;
  private final JobBuddyProperties properties;
  private final JsonCodec jsonCodec;

  public ApiAuthenticationInterceptor(
      UserLoginService userLoginService, JobBuddyProperties properties, JsonCodec jsonCodec) {
    this.userLoginService = userLoginService;
    this.properties = properties;
    this.jsonCodec = jsonCodec;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    AuthenticationScope.clear();
    if (isPublicRequest(request)) {
      return true;
    }

    if (!properties.getAuth().isEnabled()) {
      establishAuthentication(request, localUser());
      return true;
    }

    if (isInternalRequest(request)) {
      establishAuthentication(request, internalUser());
      return true;
    }

    String token = AuthSessionCookie.resolveToken(request);
    AuthenticatedUser user = userLoginService.currentUser(token);
    if (user == null) {
      writeUnauthorized(response);
      return false;
    }

    establishAuthentication(request, user);
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      Exception exception) {
    AuthenticationScope.clear();
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
    byte[] expected = configured.trim().getBytes(StandardCharsets.UTF_8);
    byte[] actual = (provided == null ? "" : provided.trim()).getBytes(StandardCharsets.UTF_8);
    return MessageDigest.isEqual(expected, actual);
  }

  private void establishAuthentication(HttpServletRequest request, AuthenticatedUser user) {
    request.setAttribute(AuthenticatedUserContext.USER_ATTRIBUTE, user);
    AuthenticationScope.set(user);
    MDC.put(
        MdcContextFilter.OPERATOR_ID,
        user == null || user.getUserId() == null ? "-" : user.getUserId());
  }

  private AuthenticatedUser internalUser() {
    return new AuthenticatedUser(
        properties.getDefaultUserId(), "internal", "Internal Service", "system");
  }

  private AuthenticatedUser localUser() {
    return new AuthenticatedUser(properties.getDefaultUserId(), "local", "Local User", "local");
  }

  private void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonCodec.toJson(ApiResponse.error(401, "未登录或登录已过期")));
  }
}
