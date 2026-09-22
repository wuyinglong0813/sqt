package com.tradepass.framework.config;

import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.framework.config.AuthInterceptor;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.service.auth.AuthSessionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthInterceptorTest {
    private AuthSessionService sessionService;
    private CompanyMemberMapper memberMapper;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        sessionService = mock(AuthSessionService.class);
        memberMapper = mock(CompanyMemberMapper.class);
        interceptor = new AuthInterceptor(sessionService, memberMapper);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void rejectsMissingOrInvalidSessionWithJson401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(sessionService.resolveUserId(null)).thenReturn(null);

        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("\"code\":401", "未登录或登录已失效");
        assertThat(AuthContext.isLoggedIn()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesRequestedCompanyOnlyWhenMembershipIsActive() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        request.addHeader("X-CompanyDO-Id", "9");
        when(sessionService.resolveUserId("Bearer token")).thenReturn(7L);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(AuthContext.userId()).isEqualTo(7L);
        assertThat(AuthContext.companyId()).isEqualTo(9L);
        verify(memberMapper, never()).selectList(any(Wrapper.class));

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertThat(AuthContext.isLoggedIn()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInvalidOrUnauthorizedCompanyHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "token");
        request.addHeader("X-CompanyDO-Id", "not-a-number");
        when(sessionService.resolveUserId("token")).thenReturn(7L);

        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":403", "无权访问指定企业");
        assertThat(AuthContext.isLoggedIn()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void fallsBackToFirstActiveCompanyOnlyWhenHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "token");
        when(sessionService.resolveUserId("token")).thenReturn(7L);
        CompanyMemberDO member = new CompanyMemberDO();
        member.setCompanyId(3L);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of(member));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(AuthContext.companyId()).isEqualTo(3L);
    }

    @Test
    void removedMemberCannotReuseTokenForOldCompanyButCanReadPersonalNotices() throws Exception {
        when(sessionService.resolveUserId("token")).thenReturn(7L);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(memberMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        MockHttpServletRequest business = new MockHttpServletRequest("GET", "/api/contracts");
        business.addHeader("Authorization", "token");
        business.addHeader("X-CompanyDO-Id", "3");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(business, denied, new Object())).isFalse();
        assertThat(denied.getStatus()).isEqualTo(403);
        MockHttpServletRequest notices = new MockHttpServletRequest("GET", "/api/me/membership-notices");
        notices.addHeader("Authorization", "token");
        assertThat(interceptor.preHandle(notices, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(AuthContext.userId()).isEqualTo(7L);
        assertThat(AuthContext.companyId()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void toleratesMembershipLookupFailureWithoutLeakingAnotherTenant() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "token");
        when(sessionService.resolveUserId("token")).thenReturn(7L);
        when(memberMapper.selectList(any(Wrapper.class))).thenThrow(new IllegalStateException("db down"));

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(AuthContext.companyId()).isNull();
    }
}
