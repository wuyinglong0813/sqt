package com.tradepass.module.identity.service.user;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserIdentityServiceTest {
    private SysUserMapper userMapper;
    private CompanyMapper companyMapper;
    private FadadaUserIdentityMapper identityMapper;
    private UserIdentityService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(SysUserMapper.class);
        companyMapper = mock(CompanyMapper.class);
        identityMapper = mock(FadadaUserIdentityMapper.class);
        service = new UserIdentityServiceImpl(userMapper, companyMapper);
        service.setIdentityMapper(identityMapper);
        AuthContext.set(8L, 4L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void returnsVerifiedPersonalName() {
        FadadaUserIdentityDO identity = new FadadaUserIdentityDO();
        identity.setUserId(8L);
        identity.setLocalStatus("VERIFIED");
        identity.setVerifiedName(" 张采购 ");
        when(identityMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(identity);

        assertThat(service.requireCurrentVerifiedName(4L)).isEqualTo("张采购");
    }

    @Test
    void refusesConfirmationUntilPersonalIdentityCompletes() {
        when(identityMapper.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(null);

        assertThatThrownBy(() -> service.requireCurrentVerifiedName(4L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先完成个人认证，再确认业务单据");
    }
}
