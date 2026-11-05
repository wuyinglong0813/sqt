package com.tradepass.module.identity.service.permission;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalIdentityAccessTest {
    @Test
    void requiresVerifiedIdentityAndProviderBindingAndDoesNotCacheRevocation() {
        MybatisTestSupport.initialize(FadadaUserIdentityDO.class);
        var mapper = mock(FadadaUserIdentityMapper.class);
        var access = new PersonalIdentityAccess(mapper);
        assertThat(access.isVerified(7L)).isFalse();
        var identity = new FadadaUserIdentityDO();
        identity.setUserId(7L);
        when(mapper.selectOne(any(Wrapper.class))).thenReturn(identity);
        identity.setLocalStatus("VERIFIED");
        assertThat(access.isVerified(7L)).isFalse();
        identity.setOpenUserId("provider-person-7");
        identity.setLocalStatus("IN_PROGRESS");
        assertThat(access.isVerified(7L)).isFalse();
        identity.setLocalStatus("VERIFIED");
        assertThat(access.isVerified(7L)).isTrue();
        identity.setLocalStatus("FAILED");
        assertThat(access.isVerified(7L)).isFalse();
    }
}
