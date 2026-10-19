package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.core.FadadaCompanyGateway;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.api.permission.AccessControlOperations.EffectiveRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FadadaSigningMemberServiceTest {
    @AfterEach void clearContext() { AuthContext.clear(); }

    @Test
    void anExistingActivatedMemberCanSignWithoutBecomingARemoteAdministratorOrRegisteringAgain() {
        var f = new Fixture();
        f.member(88L, "activated", "another-person");
        f.member(89L, "activated", "current-fdd-id");
        assertThatCode(() -> f.service.requireCurrentSigningMember(3L)).doesNotThrowAnyException();
        verify(f.gateway).listMemberIds("corp-3", "武英龙");
        verify(f.gateway).getMember("corp-3", 89L);
        verify(f.gateway, never()).createAuthUrl(any());
        verify(f.gateway, never()).createOrganizationManageUrl(anyString(), anyString());
        verify(f.access, never()).requireManager(anyLong());
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "disabled", "", "unknown"})
    void anUnjoinedOrDisabledMemberGetsAnActionableMessageBeforeOpeningTheProviderTask(String state) {
        var f = new Fixture();
        f.member(88L, state, "current-fdd-id");
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L)).isInstanceOf(BusinessException.class);
        verify(f.gateway, times(1)).getMember("corp-3", 88L);
        verify(f.gateway, never()).createMember(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "same-name-different-person"})
    void aMatchingNameOrPhoneCannotSubstituteForTheVerifiedPersonalFddId(String fddId) {
        var f = new Fixture(); f.member(88L, "activated", fddId); f.member(89L, "activated", fddId);
        when(f.personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson("open-7", "current-fdd-id", "武英龙"));
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L))
                .hasMessageContaining("可关联的手机号或邮箱");
        verify(f.gateway, never()).createMember(any());
    }

    @Test
    void absentMembersAreAutomaticallyCreatedUsingTheCurrentVerifiedProviderAccount() {
        var f = new Fixture(); when(f.gateway.listMemberIds(anyString(), nullable(String.class))).thenReturn(List.of());
        when(f.gateway.createMember(any())).thenReturn(new FadadaCompanyGateway.MemberInvitation(90L, "tradepass-user-7", "activated", null));
        when(f.gateway.getMember("corp-3", 90L)).thenReturn(f.linked(90L, "activated", "current-fdd-id"));
        assertThat(f.service.ensureSigningMember(3L, 7L).memberId()).isEqualTo(90L);
        verify(f.gateway).createMember(argThat(command -> command.accountName().equals("13800138000")
                && command.internalIdentifier().equals("tradepass-user-7") && command.memberName().equals("武英龙")));
    }

    @Test
    void aCorporateNicknameStillMatchesByPersonalFddIdWithoutRegisteringTheMemberAgain() {
        var f = new Fixture();
        when(f.gateway.listMemberIds("corp-3", "武英龙")).thenReturn(List.of(88L));
        when(f.gateway.listMemberIds("corp-3", null)).thenReturn(List.of(88L, 89L));
        f.member(89L, "activated", "current-fdd-id");
        assertThatCode(() -> f.service.requireCurrentSigningMember(3L)).doesNotThrowAnyException();
        verify(f.gateway, times(1)).getMember("corp-3", 88L);
        verify(f.gateway).getMember("corp-3", 89L);
    }

    @Test
    void anUnverifiedLocalMemberCannotQueryRemoteCompanyOrPersonalData() {
        var f = new Fixture(); when(f.access.isActiveMember(3L, 7L)).thenReturn(false);
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L)).hasMessage("该账号不是企业的有效成员");
        verifyNoInteractions(f.companies, f.personal, f.gateway);
    }

    @Test
    void anIncompleteCurrentPersonalIdentityCannotBeMatchedUsingOtherAccountData() {
        var f = new Fixture();
        when(f.personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson("open-7", null, "武英龙"));
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L)).hasMessageContaining("个人认证");
        verifyNoInteractions(f.companies, f.gateway);
    }

    @Test
    void changedCompanyBindingCannotQueryMembersOfAnotherEnterprise() {
        var f = new Fixture();
        when(f.gateway.getCompany(anyString(), anyString())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                "local-3", "another-corp", "authorized", "identified", "enable", List.of("organization")));
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L)).hasMessageContaining("授权状态已变化");
        verify(f.gateway, never()).listMemberIds(anyString(), anyString());
    }

    @Test
    void legacyCompanyWithoutOrganizationConsentMustObtainConsentBeforeReadingMembers() {
        var f = new Fixture();
        when(f.gateway.getCompany(anyString(), anyString())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                "local-3", "corp-3", "authorized", "identified", "enable", List.of("ident_info")));
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L)).hasMessageContaining("补充组织数据授权");
        verify(f.gateway, never()).listMemberIds(anyString(), anyString());
    }

    @Test
    void aBackgroundReconciliationReusesTheSamePersonWithoutRequestContextOrAnotherInvitation() {
        var f = new Fixture();
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7"))
                .thenReturn(f.linked(90L, "activated", "current-fdd-id"));
        AuthContext.clear();
        assertThat(f.service.reconcileLocalSigningMember(3L, 7L).memberId()).isEqualTo(90L);
        verify(f.access, never()).requireManager(anyLong());
        verify(f.gateway, never()).createMember(any());
    }

    @Test
    void backgroundWorkRechecksCurrentLocalPermissionBeforeReadingRemotePersonalOrCompanyData() {
        var f = new Fixture();
        when(f.access.effectiveRole(3L, 7L)).thenReturn(new EffectiveRole("GUEST", "访客", List.of()));
        AuthContext.clear();
        assertThatThrownBy(() -> f.service.reconcileLocalSigningMember(3L, 7L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(46105));
        verifyNoInteractions(f.personal, f.companies, f.gateway);
    }

    @Test
    void anAlreadyAuthorizedResponseStillRequiresEvidenceOfOrganizationConsent() {
        var f = new Fixture();
        when(f.gateway.getCompany("local-3", "corp-3"))
                .thenThrow(new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210002"));
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getCode()).isEqualTo(46101));
        verify(f.gateway, never()).listMemberIds(anyString(), anyString());
        verify(f.gateway, never()).createMember(any());
    }

    @Test
    void memberDetailsMustBelongToTheRequestedMemberId() {
        var f = new Fixture();
        when(f.gateway.getMember("corp-3", 88L)).thenReturn(new FadadaCompanyGateway.CompanyMember(
                999L, "activated", List.of(), "current-fdd-id"));
        assertThatThrownBy(() -> f.service.requireCurrentSigningMember(3L)).hasMessageContaining("信息不一致");
    }

    @Test
    void organizationEditorRequiresLocalManagementAndKeepsTheCurrentUsersProviderMapping() {
        var f = new Fixture();
        when(f.gateway.createOrganizationManageUrl("corp-3", "tradepass-user-7")).thenReturn("https://example.test/member-editor");
        var result = f.service.createOrganizationManageUrl(3L);
        assertThat(result.url()).isEqualTo("https://example.test/member-editor");
        assertThat(result.scene()).isEqualTo("organization");
        assertThat(result.status()).isEqualTo("MANAGE");
        verify(f.access).requireManager(3L);
        verifyNoInteractions(f.personal);
        verify(f.gateway, never()).listMemberIds(anyString(), anyString());
    }

    @Test
    void targetPermissionsAndIdentityAreCheckedInsteadOfUsingTheCallingManagersIdentity() {
        var f = new Fixture();
        when(f.access.isActiveMember(3L, 9L)).thenReturn(true);
        when(f.access.effectiveRole(3L, 9L)).thenReturn(new EffectiveRole("SIGNER", "签署人", List.of("contract_sign")));
        when(f.personal.verifiedPerson(9L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson("open-9", "target-fdd", "目标成员", "target@example.test"));
        var member = new FadadaCompanyGateway.CompanyMember(99L, "activated", List.of(), "target-fdd", "tradepass-user-9", List.of("target@example.test"));
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-9")).thenReturn(member);
        assertThat(f.service.ensureSigningMember(3L, 9L)).isEqualTo(member);
        verify(f.access).requireManager(3L);
        verify(f.personal, never()).verifiedPerson(7L);
    }

    @Test
    void targetWithoutLocalSigningPermissionCannotBeProvisionedByAManager() {
        var f = new Fixture();
        when(f.access.isActiveMember(3L, 9L)).thenReturn(true);
        when(f.access.effectiveRole(3L, 9L)).thenReturn(new EffectiveRole("MEMBER", "普通成员", List.of("contract_view")));
        assertThatThrownBy(() -> f.service.ensureSigningMember(3L, 9L)).hasMessageContaining("签署权限");
        verifyNoInteractions(f.personal, f.gateway, f.companies);
    }

    @Test
    void timeoutRecoversTheDeterministicMemberAndNeverRepeatsTheCreate() {
        var f = new Fixture();
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7"))
                .thenReturn(null).thenReturn(f.linked(90L, "activated", "current-fdd-id"));
        when(f.gateway.createMember(any())).thenThrow(new BusinessException("provider timeout"));
        assertThat(f.service.ensureSigningMember(3L, 7L).memberId()).isEqualTo(90L);
        verify(f.gateway, times(1)).createMember(any());
        verify(f.gateway, times(2)).findMemberByInternalIdentifier("corp-3", "tradepass-user-7");
    }

    @Test
    void uncertainCreateRemainsPendingWhenTheProviderHasNotYetExposedItsResult() {
        var f = new Fixture(); when(f.gateway.createMember(any())).thenThrow(new BusinessException("provider timeout"));
        assertThatThrownBy(() -> f.service.ensureSigningMember(3L, 7L)).hasMessageContaining("同步结果待确认");
        verify(f.gateway, times(1)).createMember(any());
    }

    @Test
    void retryReusesTheExistingDeterministicAssociationInsteadOfCreatingAgain() {
        var f = new Fixture();
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7"))
                .thenReturn(f.linked(90L, "activated", "current-fdd-id"));
        assertThat(f.service.syncCurrent(3L).status()).isEqualTo("READY");
        verify(f.gateway, never()).createMember(any());
    }

    @Test
    void aDisabledDeterministicMemberIsNotRecreatedOrEnabled() {
        var f = new Fixture();
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7"))
                .thenReturn(f.linked(90L, "disabled", "current-fdd-id"));
        assertThatThrownBy(() -> f.service.ensureSigningMember(3L, 7L)).hasMessageContaining("被禁用");
        verify(f.gateway, never()).createMember(any());
        verify(f.gateway, never()).createMemberActivationUrl(anyString(), anyLong());
    }

    @Test
    void creationMustBeFollowedByMatchingProviderPersonalIdentityEvidence() {
        var f = new Fixture();
        when(f.gateway.createMember(any())).thenReturn(new FadadaCompanyGateway.MemberInvitation(90L, "tradepass-user-7", "activated", null));
        when(f.gateway.getMember("corp-3", 90L)).thenReturn(f.linked(90L, "activated", "someone-elses-fdd"));
        assertThatThrownBy(() -> f.service.ensureSigningMember(3L, 7L)).hasMessageContaining("个人实名标识不一致");
    }

    @Test
    void theCurrentPersonCanReceiveTheirOwnH5InvitationWithoutManagerApproval() {
        var f = new Fixture();
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7"))
                .thenReturn(f.linked(90L, "inactive", null));
        when(f.gateway.createMemberActivationUrl("corp-3", 90L)).thenReturn("https://example.test/my-join-invitation");
        var result = f.service.syncCurrent(3L);
        assertThat(result.status()).isEqualTo("ACTIVATION_REQUIRED");
        assertThat(result.scene()).isEqualTo("member");
        assertThat(result.url()).isEqualTo("https://example.test/my-join-invitation");
        verify(f.access, never()).requireManager(anyLong());
        assertThatThrownBy(() -> f.service.ensureSigningMember(3L, 7L)).hasMessageContaining("请本人");
    }

    @Test
    void anUnactivatedInvitationForAnotherAccountCannotBeExposedToTheCurrentPerson() {
        var f = new Fixture();
        when(f.gateway.findMemberByInternalIdentifier("corp-3", "tradepass-user-7"))
                .thenReturn(new FadadaCompanyGateway.CompanyMember(90L, "inactive", List.of(), null,
                        "tradepass-user-7", List.of("someone-else@example.test")));
        assertThatThrownBy(() -> f.service.syncCurrent(3L)).hasMessageContaining("个人实名标识不一致");
        verify(f.gateway, never()).createMemberActivationUrl(anyString(), anyLong());
    }

    @Test
    void aFddNumberWithoutAPhoneOrEmailCannotBeUsedAsTheMemberMobile() {
        var f = new Fixture();
        when(f.personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson("open-7", "current-fdd-id", "武英龙", "C123456"));
        assertThatThrownBy(() -> f.service.syncCurrent(3L)).hasMessageContaining("绑定账号");
        verify(f.gateway, never()).createMember(any());
    }

    @Test
    void aNumericFddIdThatLooksLikeAMobileIsStillNotABoundProviderAccount() {
        var f = new Fixture();
        when(f.personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson(
                "open-7", "13800138000", "武英龙", "13800138000"));
        assertThatThrownBy(() -> f.service.syncCurrent(3L)).hasMessageContaining("绑定账号");
        verify(f.gateway, never()).createMember(any());
        verify(f.gateway, never()).createMemberActivationUrl(anyString(), anyLong());
    }

    private static class Fixture {
        final AccessControlService access = mock(AccessControlService.class);
        final FadadaCompanyService companies = mock(FadadaCompanyService.class);
        final FadadaPersonalIdentityService personal = mock(FadadaPersonalIdentityService.class);
        final FadadaCompanyGateway gateway = mock(FadadaCompanyGateway.class);
        final FadadaSigningMemberService service = new FadadaSigningMemberService(access, companies, personal, gateway);
        Fixture() {
            AuthContext.set(7L, 3L);
            var identity = new FadadaCorpIdentityDO();
            identity.setClientCorpId("local-3"); identity.setOpenCorpId("corp-3");
            when(access.isActiveMember(3L, 7L)).thenReturn(true);
            when(access.effectiveRole(3L, 7L)).thenReturn(new EffectiveRole("SIGNER", "签署人", List.of("contract_sign")));
            when(companies.requireVerified(3L)).thenReturn(identity);
            when(personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson("open-7", "current-fdd-id", "武英龙", "13800138000"));
            when(gateway.getCompany("local-3", "corp-3")).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                    "local-3", "corp-3", "authorized", "identified", "enable", List.of(
                    "ident_info", "seal_info", "signtask_init", "signtask_info", "signtask_file", "organization")));
            when(gateway.listMemberIds("corp-3", "武英龙")).thenReturn(List.of(88L, 89L));
            when(gateway.listMemberIds("corp-3", null)).thenReturn(List.of(88L, 89L));
            member(88L, "activated", "another-person"); member(89L, "activated", "another-person");
        }
        void member(long id, String status, String fddId) {
            when(gateway.getMember("corp-3", id)).thenReturn(new FadadaCompanyGateway.CompanyMember(id, status, List.of(), fddId));
        }
        FadadaCompanyGateway.CompanyMember linked(long id, String status, String fddId) {
            return new FadadaCompanyGateway.CompanyMember(id, status, List.of(), fddId,
                    "tradepass-user-7", List.of("13800138000"));
        }
    }
}
