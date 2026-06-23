package com.jobbuddy.backend;

import com.jobbuddy.backend.common.web.MdcContextFilter;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MdcContextFilterTest {

    private final MdcContextFilter filter = new MdcContextFilter();

    @Test
    void populatesMdcFromHeadersAndEchoesRequestId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-123");
        request.addHeader("X-Session-Id", "sess-456");
        request.addHeader("X-User-Id", "user-789");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> captured = new HashMap<String, String>();
        FilterChain chain = (req, res) -> {
            captured.put(MdcContextFilter.REQUEST_ID, MDC.get(MdcContextFilter.REQUEST_ID));
            captured.put(MdcContextFilter.SESSION_ID, MDC.get(MdcContextFilter.SESSION_ID));
            captured.put(MdcContextFilter.OPERATOR_ID, MDC.get(MdcContextFilter.OPERATOR_ID));
        };

        filter.doFilter(request, response, chain);

        assertEquals("req-123", captured.get(MdcContextFilter.REQUEST_ID));
        assertEquals("sess-456", captured.get(MdcContextFilter.SESSION_ID));
        assertEquals("user-789", captured.get(MdcContextFilter.OPERATOR_ID));
        assertEquals("req-123", response.getHeader("X-Request-Id"));
    }

    @Test
    void generatesRequestIdAndDefaultsWhenHeadersMissing() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Map<String, String> captured = new HashMap<String, String>();
        FilterChain chain = (req, res) -> {
            captured.put(MdcContextFilter.REQUEST_ID, MDC.get(MdcContextFilter.REQUEST_ID));
            captured.put(MdcContextFilter.SESSION_ID, MDC.get(MdcContextFilter.SESSION_ID));
        };

        filter.doFilter(request, response, chain);

        assertNotNull(captured.get(MdcContextFilter.REQUEST_ID));
        assertTrue(captured.get(MdcContextFilter.REQUEST_ID).length() > 0);
        assertEquals("-", captured.get(MdcContextFilter.SESSION_ID));
    }

    @Test
    void clearsMdcAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "req-clear");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(MDC.get(MdcContextFilter.REQUEST_ID));
        assertNull(MDC.get(MdcContextFilter.SESSION_ID));
        assertNull(MDC.get(MdcContextFilter.OPERATOR_ID));
    }
}
