package com.harmoniasuite.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NoCacheStaticFilterTest {

    private final NoCacheStaticFilter filter = new NoCacheStaticFilter();

    @Test
    @DisplayName("static files get no-cache")
    void staticFilesGetNoCache() throws Exception {
        assertNoCache("/js/app.js");
        assertNoCache("/css/app.css");
        assertNoCache("/img/yuki-icon.png");
        assertNoCache("/index.html");
    }

    @Test
    @DisplayName("root welcome page gets no-cache too")
    void rootGetsNoCache() throws Exception {
        assertNoCache("/");
    }

    @Test
    @DisplayName("api responses are left untouched")
    void apiUntouched() throws Exception {
        MockHttpServletRequest request = request("/api/status");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (chainRequest, chainResponse) -> {
        });
        assertNull(response.getHeader("Cache-Control"));
    }

    private void assertNoCache(String uri) throws Exception {
        MockHttpServletRequest request = request(uri);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (chainRequest, chainResponse) -> {
        });
        assertEquals("no-cache", response.getHeader("Cache-Control"));
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}