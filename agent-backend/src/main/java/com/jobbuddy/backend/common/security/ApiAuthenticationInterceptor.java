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

/**
 * 解析 API 会话并绑定请求级用户上下文。
 *
 * <p>公开接口由配置排除；鉴权失败在进入 Controller 前返回，避免下游读到不完整身份。
 */
@Component
public class ApiAuthenticationInterceptor implements HandlerInterceptor {
  private final UserLoginService userLoginService;
  private final JobBuddyProperties properties;
  private final JsonCodec jsonCodec;

  /**
   * 创建 API 认证拦截器实例。
   *
   * @param userLoginService 用户登录服务
   * @param properties 配置属性
   * @param jsonCodec JSON 编解码器
   */
  public ApiAuthenticationInterceptor(
      UserLoginService userLoginService, JobBuddyProperties properties, JsonCodec jsonCodec) {
    this.userLoginService = userLoginService;
    this.properties = properties;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 在进入 Controller 前完成请求鉴权。
   *
   * @param request 请求参数
   * @param response 响应数据
   * @param handler 处理器
   * @return 是否继续处理请求
   * @throws Exception 处理失败时抛出
   */
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

  /**
   * 请求完成后清理认证上下文。
   *
   * @param request 请求参数
   * @param response 响应数据
   * @param handler 处理器
   * @param exception 异常
   */
  @Override
  public void afterCompletion(
      HttpServletRequest request,
      HttpServletResponse response,
      Object handler,
      Exception exception) {
    AuthenticationScope.clear();
  }

  /**
   * 判断是否公开请求。
   *
   * @param request 请求参数
   * @return 是否为公开请求
   */
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

  /**
   * 判断是否内部请求。
   *
   * @param request 请求参数
   * @return 是否为内部服务请求
   */
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

  /**
   * 绑定请求级认证上下文。
   *
   * @param request 请求参数
   * @param user 用户
   */
  private void establishAuthentication(HttpServletRequest request, AuthenticatedUser user) {
    request.setAttribute(AuthenticatedUserContext.USER_ATTRIBUTE, user);
    AuthenticationScope.set(user);
    MDC.put(
        MdcContextFilter.OPERATOR_ID,
        user == null || user.getUserId() == null ? "-" : user.getUserId());
  }

  /**
   * 创建内部服务用户身份。
   *
   * @return 内部服务用户
   */
  private AuthenticatedUser internalUser() {
    return new AuthenticatedUser(
        properties.getDefaultUserId(), "internal", "Internal Service", "system");
  }

  /**
   * 创建本地开发用户身份。
   *
   * @return 本地开发用户
   */
  private AuthenticatedUser localUser() {
    return new AuthenticatedUser(properties.getDefaultUserId(), "local", "Local User", "local");
  }

  /**
   * 写入未认证响应。
   *
   * @param response 响应数据
   * @throws IOException 文件读写失败时抛出
   */
  private void writeUnauthorized(HttpServletResponse response) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonCodec.toJson(ApiResponse.error(401, "未登录或登录已过期")));
  }
}
