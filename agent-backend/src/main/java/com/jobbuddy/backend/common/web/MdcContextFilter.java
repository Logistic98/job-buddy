package com.jobbuddy.backend.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 将关联标识写入日志 MDC，使请求日志可按 request_id、session_id 和 operator_id 追踪。
 *
 * <p>关联标识可来自请求头，但 operator_id 仅在鉴权成功后替换；请求结束时统一清理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcContextFilter extends OncePerRequestFilter {

  public static final String REQUEST_ID = "request_id";
  public static final String SESSION_ID = "session_id";
  public static final String OPERATOR_ID = "operator_id";
  public static final String RUN_ID = "run_id";

  private static final String HEADER_REQUEST_ID = "X-Request-Id";
  private static final String HEADER_SESSION_ID = "X-Session-Id";
  private static final String HEADER_RUN_ID = "X-Run-Id";

  /**
   * 绑定请求日志上下文并执行过滤链。
   *
   * @param request 请求参数
   * @param response 响应数据
   * @param chain 过滤链
   * @throws ServletException 处理失败时抛出
   * @throws IOException 文件读写失败时抛出
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String requestId =
        firstNonBlank(request.getHeader(HEADER_REQUEST_ID), UUID.randomUUID().toString());
    String sessionId =
        firstNonBlank(
            request.getHeader(HEADER_SESSION_ID),
            firstNonBlank(request.getParameter("session_id"), request.getParameter("sessionId")));
    String runId =
        firstNonBlank(
            request.getHeader(HEADER_RUN_ID),
            firstNonBlank(request.getParameter("run_id"), request.getParameter("runId")));

    MDC.put(REQUEST_ID, requestId);
    MDC.put(SESSION_ID, sessionId == null ? "-" : sessionId);
    MDC.put(OPERATOR_ID, "-");
    MDC.put(RUN_ID, runId == null ? "-" : runId);
    response.setHeader(HEADER_REQUEST_ID, requestId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(REQUEST_ID);
      MDC.remove(SESSION_ID);
      MDC.remove(OPERATOR_ID);
      MDC.remove(RUN_ID);
    }
  }

  /**
   * 读取首个非空白文本。
   *
   * @param primary 主值
   * @param fallback 降级
   * @return 首个非空白文本
   */
  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.trim().isEmpty()) return primary.trim();
    if (fallback != null && !fallback.trim().isEmpty()) return fallback.trim();
    return null;
  }
}
