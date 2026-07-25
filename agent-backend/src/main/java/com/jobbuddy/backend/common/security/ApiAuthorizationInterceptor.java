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

/**
 * 在鉴权完成后、调用 Controller 前执行 {@link RequirePermission} 权限校验。
 *
 * <p>受保护接口缺少有效请求身份时按拒绝处理。
 */
@Component
public class ApiAuthorizationInterceptor implements HandlerInterceptor {
  private final JsonCodec jsonCodec;

  /**
   * 创建 API 授权拦截器实例。
   *
   * @param jsonCodec JSON 编解码器
   */
  public ApiAuthorizationInterceptor(JsonCodec jsonCodec) {
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

  /**
   * 解析处理方法上的权限注解。
   *
   * @param method HTTP 方法
   * @param type 类型
   * @return 权限注解
   */
  private <A extends java.lang.annotation.Annotation> A annotation(
      HandlerMethod method, Class<A> type) {
    A value = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
    return value != null
        ? value
        : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
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
    response.getWriter().write(jsonCodec.toJson(ApiResponse.error(401, "缺少认证上下文")));
  }

  /**
   * 写入无权限响应。
   *
   * @param response 响应数据
   * @param message 消息内容
   * @throws IOException 文件读写失败时抛出
   */
  private void writeForbidden(HttpServletResponse response, String message) throws IOException {
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setCharacterEncoding("UTF-8");
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(jsonCodec.toJson(ApiResponse.error(403, message)));
  }
}
