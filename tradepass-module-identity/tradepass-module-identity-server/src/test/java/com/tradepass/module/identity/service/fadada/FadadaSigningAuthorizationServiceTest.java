package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaCompanyGateway.CompanyMember;
import com.tradepass.framework.fadada.core.FadadaSealAuthorizationGateway;
import com.tradepass.framework.fadada.core.FadadaSealAuthorizationGateway.MemberSealGrant;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FadadaSigningAuthorizationServiceTest {
    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test
    void alreadyEffectiveSealPermissionIsReusedWithoutOpeningAnotherApproval() {
        var f = new Fixture();
        when(f.seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of(
                new MemberSealGrant("912", "enable", "effective")));

        var result = f.service.memberSealGrantUrl(3L, 7L);

        assertThat(result.status()).isEqualTo("READY"); assertThat(result.url()).isNull();
        verify(f.members).ensureSigningMember(3L, 7L);
        verify(f.seals, never()).memberSealGrantUrl(anyString(), anyLong(), anyLong(), anyString());
    }

    @ParameterizedTest @ValueSource(strings = {"ineffective", "to_be_effective"})
    void anExpiredOrNotYetEffectiveGrantNeedsOneActualManagerConfirmation(String status) {
        var f = new Fixture();
        when(f.seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of(
                new MemberSealGrant("912", "enable", status)));

        var result = f.service.memberSealGrantUrl(3L, 7L);

        assertThat(result.status()).isEqualTo("AUTHORIZATION_REQUIRED");
        assertThat(result.url()).isEqualTo("https://example.test/grant");
        verify(f.seals).memberSealGrantUrl("corp-3", 912L, 88L, "tradepass-user-99");
    }

    @Test
    void anotherSealOrADisabledSealDoesNotAuthorizeTheConfiguredSigningSeal() {
        var f = new Fixture();
        when(f.seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of(
                new MemberSealGrant("800", "enable", "effective"),
                new MemberSealGrant("912", "disable", "effective")));
        assertThat(f.service.memberSealGrantUrl(3L, 7L).status()).isEqualTo("AUTHORIZATION_REQUIRED");
    }

    @Test
    void grantForAnExistingContractDoesNotSwitchToTheNewDefaultSeal() {
        var f = new Fixture();
        when(f.seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of(new MemberSealGrant("912", "enable", "effective")));
        when(f.seals.memberSealGrantUrl("corp-3", 801L, 88L, "tradepass-user-99"))
                .thenReturn("https://example.test/old-seal");
        assertThat(f.service.memberSealGrantUrl(3L, 7L, "801").url()).isEqualTo("https://example.test/old-seal");
        verify(f.companies, never()).enabledSealId(anyLong());
        verify(f.seals).memberSealGrantUrl("corp-3", 801L, 88L, "tradepass-user-99");
    }

    @Test
    void returningFromTheEditorOnlyCompletesWhenAFreshProviderQueryConfirmsTheGrant() {
        var f = new Fixture();
        when(f.seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of(),
                List.of(new MemberSealGrant("912", "enable", "effective")));

        assertThat(f.service.memberSealGrantUrl(3L, 7L).status()).isEqualTo("AUTHORIZATION_REQUIRED");
        assertThat(f.service.memberSealGrantUrl(3L, 7L).status()).isEqualTo("READY");

        verify(f.seals, times(1)).memberSealGrantUrl("corp-3", 912L, 88L, "tradepass-user-99");
        verify(f.seals, times(2)).memberSealGrants("corp-3", 88L);
    }

    @Test
    void localNonManagersCannotEnrollOrAuthorizeAnotherMember() {
        var f = new Fixture();
        doThrow(new BusinessException("无成员管理权限")).when(f.access).requireManager(3L);

        assertThatThrownBy(() -> f.service.memberSealGrantUrl(3L, 7L)).hasMessageContaining("管理权限");

        verifyNoInteractions(f.members, f.companies, f.seals);
    }

    @Test
    void memberManagementWithoutSealManagementCannotGrantTheSeal() {
        var f = new Fixture();
        doThrow(new BusinessException("无印章管理权限")).when(f.access).requirePermission(3L, "seal_manage");

        assertThatThrownBy(() -> f.service.memberSealGrantUrl(3L, 7L)).hasMessageContaining("印章管理权限");

        verifyNoInteractions(f.members, f.companies, f.seals);
    }

    @Test
    void signingAutomaticallyEnsuresTheCurrentEmployeeThenReusesAnEffectiveGrant() {
        var f = new Fixture(); AuthContext.set(7L, 3L);
        when(f.seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of(new MemberSealGrant("912", "enable", "effective")));

        f.service.requireCurrentSigningAuthorization(3L);

        verify(f.members).ensureSigningMember(3L, 7L);
        verify(f.access).requirePermission(3L, "contract_sign");
        verify(f.seals, never()).memberSealGrantUrl(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void missingUsePermissionExplainsTheOneTimeGrantWithoutOpeningItForTheSigner() {
        var f = new Fixture(); AuthContext.set(7L, 3L);

        assertThatThrownBy(() -> f.service.requireCurrentSigningAuthorization(3L))
                .isInstanceOfSatisfying(com.tradepass.framework.common.exception.BusinessException.class,
                        error -> assertThat(error.getCode()).isEqualTo(46104))
                .hasMessageContaining("印章管理员", "授权有效期间无需重复办理");

        verify(f.seals, never()).memberSealGrantUrl(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void revokedLocalSigningPermissionStopsTheFlowBeforeOpeningAProviderGrant() {
        var f = new Fixture();
        when(f.members.ensureSigningMember(3L, 7L)).thenThrow(new BusinessException("该成员没有合同签署权限"));

        assertThatThrownBy(() -> f.service.memberSealGrantUrl(3L, 7L)).hasMessageContaining("签署权限");

        verifyNoInteractions(f.companies, f.seals);
    }

    private static class Fixture {
        final AccessControlService access = mock(AccessControlService.class);
        final FadadaSigningMemberService members = mock(FadadaSigningMemberService.class);
        final FadadaCompanyService companies = mock(FadadaCompanyService.class);
        final FadadaSealAuthorizationGateway seals = mock(FadadaSealAuthorizationGateway.class);
        final FadadaSigningAuthorizationService service = new FadadaSigningAuthorizationService(access, members, companies, seals);
        Fixture() {
            AuthContext.set(99L, 3L);
            var identity = new FadadaCorpIdentityDO(); identity.setOpenCorpId("corp-3");
            when(companies.requireVerified(3L)).thenReturn(identity);
            when(companies.enabledSealId(3L)).thenReturn("912");
            when(members.ensureSigningMember(3L, 7L)).thenReturn(new CompanyMember(88L, "activated", List.of(), "person-7"));
            when(seals.memberSealGrants("corp-3", 88L)).thenReturn(List.of());
            when(seals.memberSealGrantUrl("corp-3", 912L, 88L, "tradepass-user-99"))
                    .thenReturn("https://example.test/grant");
        }
    }
}
