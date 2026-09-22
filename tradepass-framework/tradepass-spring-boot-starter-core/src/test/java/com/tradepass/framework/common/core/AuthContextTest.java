package com.tradepass.framework.common.core;

import com.tradepass.framework.common.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthContextTest {

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void storesAndClearsRequestPrincipal() {
        AuthContext.set(7L, 11L);

        assertThat(AuthContext.isLoggedIn()).isTrue();
        assertThat(AuthContext.userId()).isEqualTo(7L);
        assertThat(AuthContext.companyId()).isEqualTo(11L);
        assertThat(AuthContext.requireCompanyId()).isEqualTo(11L);

        AuthContext.clear();
        assertThat(AuthContext.isLoggedIn()).isFalse();
        assertThat(AuthContext.companyId()).isNull();
    }

    @Test
    void rejectsMissingUserAndCompany() {
        assertThatThrownBy(AuthContext::userId)
                .isInstanceOf(BusinessException.class)
                .hasMessage("未登录或登录已失效");

        AuthContext.set(7L, null);
        assertThatThrownBy(AuthContext::requireCompanyId)
                .isInstanceOf(BusinessException.class)
                .hasMessage("尚未选择企业");
    }
}
