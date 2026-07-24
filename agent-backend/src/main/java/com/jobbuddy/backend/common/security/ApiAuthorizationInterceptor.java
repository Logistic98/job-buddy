package com.jobbuddy.backend.common.security;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.util.JsonCodec;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiAuthorizationInterceptor implements HandlerInterceptor {
  private final JsonCodec jsonCodec;

  public ApiAuthorizationInterceptor(JsonCodec jsonCodec) {
    this.jsonCodec = jsonCodec;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if (!(handler instanceof HandlerMethod)) return true;
    HandlerMethod method = (HandlerMethod) handler;
    RequirePermission permission = annotation(method, RequirePermission.class);
    if (permission == null) return true;

    AuthenticatedUser user;
    try {
      user = AuthenticatedUserContext.user(request);
    } catch (IllegalArgumentException missingAuthentication) {
      writeUnauthorized(response);
      return false;
    }
    if (!user.hasPermission(permission.value())) {
      writeForbidden(response, "当前账号未获得所需访问权限");
      return false;
    }
    return true;
  }

  private <A extends java.lang.annotation.Annotation> A annotation(
      HandlerMethod method, Class<A> type) {
    A value = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
    return value != null
        ? value
        : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
  }

  private void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonCodec.toJson(ApiResponse.error(401, "缺少认证上下文")));
  }

  private void writeForbidden(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonCodec.toJson(ApiResponse.error(403, message)));
  }
}
