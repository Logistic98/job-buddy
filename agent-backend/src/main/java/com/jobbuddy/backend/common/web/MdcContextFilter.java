package com.jobbuddy.backend.common.web;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Populates the logging MDC with correlation identifiers so every log line emitted while
 * handling a request can be tied back to a request_id / session_id / operator_id. Identifiers
 * are read from inbound headers when present and otherwise generated, then cleared after the
 * request completes to avoid leaking context across pooled threads.
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
    private static final String HEADER_OPERATOR_ID = "X-User-Id";
    private static final String HEADER_RUN_ID = "X-Run-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = firstNonBlank(request.getHeader(HEADER_REQUEST_ID), UUID.randomUUID().toString());
        String sessionId = firstNonBlank(request.getHeader(HEADER_SESSION_ID),
                firstNonBlank(request.getParameter("session_id"), request.getParameter("sessionId")));
        String operatorId = firstNonBlank(request.getHeader(HEADER_OPERATOR_ID), "-");
        String runId = firstNonBlank(request.getHeader(HEADER_RUN_ID),
                firstNonBlank(request.getParameter("run_id"), request.getParameter("runId")));

        MDC.put(REQUEST_ID, requestId);
        MDC.put(SESSION_ID, sessionId == null ? "-" : sessionId);
        MDC.put(OPERATOR_ID, operatorId);
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

    private static String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.trim().isEmpty()) return primary.trim();
        if (fallback != null && !fallback.trim().isEmpty()) return fallback.trim();
        return null;
    }
}
