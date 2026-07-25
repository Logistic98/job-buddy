package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.ApiAuthenticationInterceptor;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.common.web.MdcContextFilter;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 ApiAuthenticationInterceptor 的核心行为、异常路径与边界条件。
 */
class ApiAuthenticationInterceptorUnitTest {
  /**
   * 验证 ApiAuthenticationInterceptor 中用户的身份认证与会话边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void disabledAuthInjectsDefaultLocalUserForProtectedApis() throws Exception {
    UserLoginService userLoginService = mock(UserLoginService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getAuth().setEnabled(false);
    ApiAuthenticationInterceptor interceptor =
        new ApiAuthenticationInterceptor(userLoginService, properties, new JsonCodec());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resume");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean accepted = interceptor.preHandle(request, response, new Object());

    assertTrue(accepted);
    AuthenticatedUser user = AuthenticatedUserContext.user(request);
    assertEquals("default-user", user.getUserId());
    assertEquals("local", user.getRole());
    verifyNoInteractions(userLoginService);
  }

  /**
   * 验证 ApiAuthenticationInterceptor 中认证的身份认证与会话边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void internalTokenRequiresExactValueAndSetsAuthenticatedOperator() throws Exception {
    UserLoginService userLoginService = mock(UserLoginService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getAuth().setEnabled(true);
    properties.getAuth().setInternalApiToken("internal-secret");
    ApiAuthenticationInterceptor interceptor =
        new ApiAuthenticationInterceptor(userLoginService, properties, new JsonCodec());

    MockHttpServletRequest valid = new MockHttpServletRequest("GET", "/api/resume");
    valid.addHeader("X-Internal-Api-Token", "internal-secret");
    assertTrue(interceptor.preHandle(valid, new MockHttpServletResponse(), new Object()));
    assertEquals("default-user", MDC.get(MdcContextFilter.OPERATOR_ID));
    interceptor.afterCompletion(valid, new MockHttpServletResponse(), new Object(), null);

    MockHttpServletRequest invalid = new MockHttpServletRequest("GET", "/api/resume");
    invalid.addHeader("X-Internal-Api-Token", "internal-secret-x");
    assertFalse(interceptor.preHandle(invalid, new MockHttpServletResponse(), new Object()));
  }

  /**
   * 验证 ApiAuthenticationInterceptor 中认证的持久化与状态变更规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void bearerAuthenticationOverwritesUntrustedOperatorHeader() throws Exception {
    UserLoginService userLoginService = mock(UserLoginService.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getAuth().setEnabled(true);
    when(userLoginService.currentUser("token-1"))
        .thenReturn(new AuthenticatedUser("real-user", "tenant-a", "Real User", "user"));
    ApiAuthenticationInterceptor interceptor =
        new ApiAuthenticationInterceptor(userLoginService, properties, new JsonCodec());
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resume");
    request.addHeader("Authorization", "Bearer token-1");
    request.addHeader("X-User-Id", "spoofed-user");

    assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    assertEquals("real-user", MDC.get(MdcContextFilter.OPERATOR_ID));
    interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
  }
}
