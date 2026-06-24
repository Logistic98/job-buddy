package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.ApiAuthenticationInterceptor;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiAuthenticationInterceptorUnitTest {
    @Test
    void disabledAuthInjectsDefaultLocalUserForProtectedApis() throws Exception {
        UserLoginService userLoginService = mock(UserLoginService.class);
        JobBuddyProperties properties = new JobBuddyProperties();
        properties.getAuth().setEnabled(false);
        ApiAuthenticationInterceptor interceptor = new ApiAuthenticationInterceptor(userLoginService, properties, new JsonCodec());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/resume");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor.preHandle(request, response, new Object());

        assertTrue(accepted);
        Map<String, Object> user = AuthenticatedUserContext.user(request);
        assertEquals("default-user", user.get("userId"));
        assertEquals("local", user.get("role"));
        verifyNoInteractions(userLoginService);
    }
}
