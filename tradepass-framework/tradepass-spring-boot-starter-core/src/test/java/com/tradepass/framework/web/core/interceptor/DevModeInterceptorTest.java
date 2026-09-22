package com.tradepass.framework.web.core.interceptor;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DevModeInterceptorTest {

    @Test
    void allowsEverythingWhenDevelopmentModeIsEnabled() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dev/users");
        assertThat(new DevModeInterceptor(true)
                .preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void hidesDevelopmentEndpointsInProduction() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dev/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(new DevModeInterceptor(false).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":404", "Not Found");
    }

    @Test
    void doesNotBlockOrdinaryEndpoints() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");
        assertThat(new DevModeInterceptor(false)
                .preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
