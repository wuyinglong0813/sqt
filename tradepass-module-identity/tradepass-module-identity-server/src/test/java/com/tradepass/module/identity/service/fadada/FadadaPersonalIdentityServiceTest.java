package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.fadada.core.FadadaUserQueryException;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.FadadaAuthUrlRespDTO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaUserIdentityDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.framework.fadada.core.FadadaUserGateway;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaUserIdentityMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class FadadaPersonalIdentityServiceTest {
    private FadadaUserIdentityMapper identityMapper;
    private SysUserMapper userMapper;
    private FadadaUserGateway gateway;
    private FadadaProperties properties;
    private FadadaPersonalIdentityService service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(FadadaUserIdentityDO.class, SysUserDO.class);
        identityMapper = mock(FadadaUserIdentityMapper.class);
        userMapper = mock(SysUserMapper.class);
        gateway = mock(FadadaUserGateway.class);
        properties = enabledProperties();
        service = new FadadaPersonalIdentityServiceImpl(identityMapper,
                userMapper, gateway, properties, new ObjectMapper());
        AuthContext.set(8L, null);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void createsPersonalAuthUrlWithoutCollectingIdentityDocumentData() {
        SysUserDO user = new SysUserDO();
        user.setId(8L);
        user.setPhone("13800000000");
        when(userMapper.selectById(8L)).thenReturn(user);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            FadadaUserIdentityDO identity = invocation.getArgument(0);
            identity.setId(18L);
            return 1;
        }).when(identityMapper).insert(any(FadadaUserIdentityDO.class));
        when(gateway.createAuthUrl(any())).thenReturn(
                new FadadaUserGateway.AuthUrlResult("https://auth.fadada.com/personal/8"));

        FadadaAuthUrlRespDTO result = service.createAuthUrl();

        assertThat(result.authUrl()).isEqualTo("https://auth.fadada.com/personal/8");
        assertThat(result.identity().status()).isEqualTo("IN_PROGRESS");
        ArgumentCaptor<FadadaUserGateway.AuthUrlCommand> command =
                ArgumentCaptor.forClass(FadadaUserGateway.AuthUrlCommand.class);
        verify(gateway).createAuthUrl(command.capture());
        assertThat(command.getValue().clientUserId()).isEqualTo("tradepass-user-8");
        assertThat(command.getValue().accountName()).isEqualTo("13800000000");
        assertThat(command.getValue().callbackUrl())
                .isEqualTo("https://tradepass.example.com/api/fadada/callback");
        assertThat(java.net.URLDecoder.decode(command.getValue().redirectMiniAppUrl(),
                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("/pages/service-return/service-return?scene=personal");
    }

    @Test
    void certificationOperatorMatchingRequiresTheApplicantsVerifiedProviderIdentifier() {
        FadadaUserIdentityDO identity = new FadadaUserIdentityDO();
        identity.setUserId(7L);
        identity.setLocalStatus("VERIFIED");
        identity.setOpenUserId("open-user-7");
        when(identityMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FadadaUserIdentityDO>) invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("user_id");
            assertThat(query.getParamNameValuePairs().values()).contains(7L).doesNotContain(8L);
            return identity;
        });
        assertThat(service.verifiedOpenUserId(7L)).isEqualTo("open-user-7");
        identity.setLocalStatus("IN_PROGRESS");
        assertThatThrownBy(() -> service.verifiedOpenUserId(7L)).isInstanceOf(BusinessException.class);
        identity.setLocalStatus("VERIFIED"); identity.setOpenUserId("");
        assertThatThrownBy(() -> service.verifiedOpenUserId(7L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void allowsLocalAuthUrlVerificationBeforePublicCallbackIsConfigured() {
        properties.setCallbackUrl("");
        SysUserDO user = new SysUserDO();
        user.setId(8L);
        user.setPhone("13800000000");
        when(userMapper.selectById(8L)).thenReturn(user);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            FadadaUserIdentityDO identity = invocation.getArgument(0);
            identity.setId(18L);
            return 1;
        }).when(identityMapper).insert(any(FadadaUserIdentityDO.class));
        when(gateway.createAuthUrl(any())).thenReturn(
                new FadadaUserGateway.AuthUrlResult("https://auth.fadada.com/personal/8"));

        service.createAuthUrl();

        ArgumentCaptor<FadadaUserGateway.AuthUrlCommand> command =
                ArgumentCaptor.forClass(FadadaUserGateway.AuthUrlCommand.class);
        verify(gateway).createAuthUrl(command.capture());
        assertThat(command.getValue().callbackUrl()).isNull();
    }

    @Test
    void rejectsInvalidCallbackBeforeCallingProvider() {
        SysUserDO user = new SysUserDO();
        user.setId(8L);
        user.setPhone("13800000000");
        when(userMapper.selectById(8L)).thenReturn(user);
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(identity("NOT_STARTED"));
        for (String value : java.util.List.of("/api/fadada/callback", "http://example.test/callback",
                "https://example.test/callback#fragment", "https://user:pass@example.test/callback")) {
            properties.setCallbackUrl(value);
            org.assertj.core.api.Assertions.assertThatThrownBy(service::createAuthUrl)
                    .hasMessageContaining("认证回调地址配置不正确");
        }
        org.mockito.Mockito.verifyNoInteractions(gateway);
    }

    @Test
    void missingAccountAndRateLimitRemainUnverifiedAndUseCooldown() {
        for (String code : java.util.List.of("210022", "100020")) {
            org.mockito.Mockito.reset(gateway);
            FadadaUserIdentityDO identity = identity("IN_PROGRESS");
            when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(identity);
            when(gateway.getUser("tradepass-user-8", null)).thenThrow(
                    new com.tradepass.framework.fadada.core.FadadaUserQueryException(code));
            assertThat(service.syncCurrent().status()).isEqualTo("IN_PROGRESS");
            assertThat(identity.getFailureReason()).isNotBlank();
            assertThat(service.syncCurrent().status()).isEqualTo("IN_PROGRESS");
            verify(gateway, org.mockito.Mockito.times(1)).getUser("tradepass-user-8", null);
            org.mockito.Mockito.verify(gateway, org.mockito.Mockito.never()).getIdentityInfo(any());
        }
    }

    @Test
    void synchronizesAuthoritativeProviderStatusAndVerifiedName() {
        FadadaUserIdentityDO identity = identity("IN_PROGRESS");
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(identity);
        when(gateway.getUser("tradepass-user-8", null)).thenReturn(
                new FadadaUserGateway.UserAccountResult("tradepass-user-8", "open-user-8",
                        "authorized", "identified", List.of("ident_info")));
        when(gateway.getIdentityInfo("open-user-8")).thenReturn(
                new FadadaUserGateway.UserIdentityResult("open-user-8", "identified", "张三",
                        "face", "2026-08-27 10:00:00", "2026-08-27T02:05:00Z"));

        PersonalIdentityRespDTO result = service.syncCurrent();

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.verifiedName()).isEqualTo("张三");
        assertThat(result.failureReason()).isNull();
        assertThat(result.verifiedAt()).isEqualTo("2026-08-27 10:05:00");
        assertThat(result.verifiedAt()).doesNotContain("T");
        assertThat(identity.getOpenUserId()).isEqualTo("open-user-8");
        verify(identityMapper).updateById(identity);
    }

    @Test
    void treatsCompletedPersonalIdentityAsTerminalWhenUserRefreshes() {
        FadadaUserIdentityDO verified = identity("VERIFIED");
        verified.setBindingStatus("authorized");
        verified.setIdentStatus("identified");
        verified.setIdentVerifiedAt(LocalDateTime.of(2026, 8, 25, 13, 13, 35));
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(verified);

        PersonalIdentityRespDTO result = service.syncCurrent();

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.verifiedAt()).isEqualTo("2026-08-25 13:13:35");
        verifyNoInteractions(gateway);
    }

    @Test
    void requiresPersonalVerificationBeforeEnterpriseOnboarding() {
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service.requireCurrentVerified())
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先完成个人实名认证，再创建或认证企业");

        FadadaUserIdentityDO verified = identity("VERIFIED");
        verified.setVerifiedName("张三");
        when(identityMapper.selectOne(any(Wrapper.class))).thenReturn(verified);
        assertThat(service.requireCurrentVerified().status()).isEqualTo("VERIFIED");
    }

    private FadadaUserIdentityDO identity(String localStatus) {
        FadadaUserIdentityDO identity = new FadadaUserIdentityDO();
        identity.setId(18L);
        identity.setUserId(8L);
        identity.setClientUserId("tradepass-user-8");
        identity.setLocalStatus(localStatus);
        identity.setBindingStatus("unauthorized");
        identity.setIdentStatus("unidentified");
        identity.setIdentProcessStatus("identifying");
        identity.setAuthScopes("[\"ident_info\"]");
        return identity;
    }

    private FadadaProperties enabledProperties() {
        FadadaProperties value = new FadadaProperties();
        value.setEnabled(true);
        value.setAppId("app-id");
        value.setAppSecret("app-secret");
        value.setServerUrl("https://api.fadada.com/api/v5");
        value.setCallbackUrl("https://tradepass.example.com/api/fadada/callback");
        return value;
    }
}
