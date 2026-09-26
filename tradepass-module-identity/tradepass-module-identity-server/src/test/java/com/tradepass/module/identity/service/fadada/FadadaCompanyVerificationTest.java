package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpSealDO;
import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import com.tradepass.module.identity.service.permission.AccessControlService;

import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpSealMapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.framework.fadada.core.FadadaCompanyGateway;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.tradepass.module.identity.service.certification.CompanyCertificationService.CertifiedApplicantRole;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FadadaCompanyVerificationTest {
    private static final List<String> SCOPES = List.of("ident_info", "seal_info", "signtask_init", "signtask_info", "signtask_file");

    @Test
    void renewingAuthorizationDoesNotTurnAnActiveCompanyBackIntoAnUnapprovedApplication() {
        var f = new Fixture();
        f.company.setCertificationStatus("VERIFIED");
        var properties = new FadadaProperties();
        properties.setEnabled(true); properties.setAppId("test-app"); properties.setAppSecret("test-secret");
        properties.setServerUrl("https://example.test"); properties.setCallbackUrl("https://example.test/callback");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/auth");
        com.tradepass.framework.common.core.AuthContext.set(7L, 3L);
        try {
            var service = new FadadaCompanyServiceImpl(f.identities, f.seals, f.companies, mock(AccessControlService.class),
                    f.certifications, f.personal, f.gateway, properties, new ObjectMapper());
            service.createAuthUrl(3L);
            verify(f.gateway).createAuthUrl(argThat(command ->
                    java.net.URLDecoder.decode(command.redirectMiniAppUrl(), java.nio.charset.StandardCharsets.UTF_8)
                            .equals("/pages/service-return/service-return?scene=company&companyId=3")));
            verify(f.companies, never()).update(any(Wrapper.class));
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void missingOperatorEvidenceIsDistinctFromAnAccountMismatchAndCanRecover() {
        var f = new Fixture();
        f.detail("legal_rep", null, "identified");
        assertThat(f.service.sync(3L).failureReason()).contains("尚未返回经办人身份");
        verifyNoInteractions(f.certifications);
        f.detail("legal_rep", "another-user", "identified");
        assertThat(f.service.sync(3L).failureReason()).contains("标识不一致");
        verifyNoInteractions(f.certifications);
        f.detail("legal_rep", "open-user-7", "identified");
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
    }

    @ParameterizedTest
    @CsvSource({"legal_rep,LEGAL", "deputy_auth,ADMIN"})
    void assignsOnlyTheRoleProvenForTheActualApplicant(String operatorType, CertifiedApplicantRole expectedRole) {
        var f = new Fixture();
        f.detail(operatorType, "open-user-7", "identified");
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), eq("FDD-CORP-local-3"), anyString(), eq(expectedRole));
        assertThat(f.identity.getOperatorType()).isEqualTo(operatorType);
        assertThat(f.identity.getOperatorId()).isEqualTo("open-user-7");
    }

    @ParameterizedTest
    @CsvSource({"legal_rep,another-user,identified", "deputy_auth,another-user,identified",
            "legal_rep,'',identified", "unknown,open-user-7,identified", "'',open-user-7,identified",
            "legal_rep,open-user-7,unidentified"})
    void incompleteOrMismatchedOperatorEvidenceNeverActivatesTheApplicant(String type, String operatorId, String status) {
        var f = new Fixture();
        f.detail(type, operatorId, status);
        var result = f.service.sync(3L);
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.failureReason()).isNotBlank();
        verifyNoInteractions(f.certifications);
        verify(f.gateway, never()).listSeals(anyString());
    }

    @ParameterizedTest
    @CsvSource({"unauthorized,true", "authorized,false"})
    void enterpriseVerificationAloneDoesNotProveAuthorization(String binding, boolean completeScopes) {
        var f = new Fixture();
        f.detail("deputy_auth", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                "local-3", "corp-3", binding, "identified", "enable", completeScopes ? SCOPES : List.of("ident_info")));
        assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void missingPersonalVerificationDoesNotActivateAnOtherwiseVerifiedCompany() {
        var f = new Fixture();
        f.detail("legal_rep", "open-user-7", "identified");
        when(f.personal.verifiedOpenUserId(7L)).thenThrow(new com.tradepass.framework.common.exception.BusinessException("个人认证尚未完成"));
        assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void mismatchedCompanyIdentityCannotGrantRoles() {
        var f = new Fixture();
        when(f.gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                "corp-3", "identified", "另一个企业", "OTHER-CREDIT", "张三", "legalRep", null, null, "legal_rep", "open-user-7"));
        assertThatThrownBy(() -> f.service.sync(3L)).hasMessageContaining("企业名称或统一社会信用代码不一致");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void successfulVerificationReplacesTheUnverifiedLegalNameWithProviderEvidence() {
        var f = new Fixture();
        f.company.setLegalPersonName("填写错误");
        f.detail("legal_rep", "open-user-7", "identified");
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
        assertThat(f.company.getLegalPersonName()).isEqualTo("张三");
        assertThat(f.identity.getVerifiedLegalRepName()).isEqualTo("张三");
        verify(f.companies, atLeastOnce()).update(argThat((Wrapper<CompanyDO> wrapper) ->
                wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> update
                        && update.getSqlSet().contains("legal_person_name")));
    }

    @Test
    void sealProviderFailureDoesNotUndoCertificationAndNextRefreshClearsTheWarning() {
        var f = new Fixture();
        f.detail("deputy_auth", "open-user-7", "identified");
        when(f.gateway.listSeals("corp-3")).thenThrow(new com.tradepass.framework.common.exception.BusinessException("查询电子印章失败"))
                .thenReturn(List.of());
        var result = f.service.sync(3L);
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.sealSyncWarning()).contains("电子印章暂未同步");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.ADMIN));
        verify(f.seals, never()).update(any(), any(Wrapper.class));
        assertThat(f.service.sync(3L).sealSyncWarning()).isEmpty();
    }

    @Test
    void lateFailureCallbackCannotRevokeCurrentlyVerifiedProviderState() {
        var f = new Fixture();
        f.company.setCertificationStatus("VERIFIED");
        f.detail("legal_rep", "open-user-7", "identified");
        assertThat(f.service.syncCallback("local-3", "corp-3", new ObjectMapper().createObjectNode()
                .put("authResult", "fail")).status()).isEqualTo("VERIFIED");
        verify(f.companies, never()).update(argThat((Wrapper<CompanyDO> wrapper) ->
                wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> update
                        && update.getSqlSet().contains("certification_status")));
    }

    @Test
    void firstFailedCertificationCanBeRecordedBeforeAProviderCompanyAccountExists() {
        var f = new Fixture();
        assertThat(f.service.syncCallback("local-3", null, new ObjectMapper().createObjectNode()
                .put("authResult", "fail").put("authFailedReason", "认证未通过")).status()).isEqualTo("FAILED");
        verify(f.gateway, never()).getCompany(any(), any());
        assertThat(f.identity.getFailureReason()).isEqualTo("认证未通过");
    }

    @Test
    void callbackReloadsTheCurrentOperatorInsteadOfItsEarlierRepeatableReadSnapshot() {
        var f = new Fixture();
        var current = new FadadaCorpIdentityDO();
        current.setId(5L); current.setCompanyId(3L); current.setClientCorpId("local-3");
        current.setApplicantUserId(9L); current.setOpenCorpId("corp-3");
        current.setProviderRequestId("FDD-LEGAL-local-3-9");
        when(f.identities.selectOne(any(Wrapper.class))).thenAnswer(call -> {
            Wrapper<?> query = call.getArgument(0);
            return query.getSqlSegment().contains("FOR UPDATE") ? current : f.identity;
        });
        when(f.personal.verifiedOpenUserId(9L)).thenReturn("open-user-9");
        f.detail("legal_rep", "open-user-9", "identified");
        assertThat(f.service.syncCallback("local-3", "corp-3", new ObjectMapper().createObjectNode()
                .put("authResult", "success")).status()).isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(9L), eq("FDD-LEGAL-local-3-9"), anyString(), eq(CertifiedApplicantRole.LEGAL));
        verify(f.identities, atLeastOnce()).updateById(current);
        verify(f.identities, never()).updateById(f.identity);
    }

    @ParameterizedTest
    @CsvSource({"legal_rep,open-user-7", "deputy_auth,open-user-9", "unknown,open-user-9"})
    void legalClaimRejectsAnotherPersonsIdentityAndAuthorizedAgentEvidence(String type, String operator) {
        var f = new Fixture();
        f.identity.setOpenCorpId("corp-3");
        f.detail(type, operator, "identified");
        when(f.personal.verifiedOpenUserId(9L)).thenReturn("open-user-9");
        com.tradepass.framework.common.core.AuthContext.set(9L, 3L);
        try {
            assertThat(f.service.syncLegalRepresentative(3L).status()).isEqualTo("IN_PROGRESS");
            verify(f.certifications, never()).completeLegalClaim(anyLong(), anyLong(), anyString());
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void verifiedLegalMemberTakesOverFutureCertificationSyncWithoutChangingTheFormerAdministrator() {
        var f = new Fixture();
        f.identity.setOpenCorpId("corp-3");
        f.detail("legal_rep", "open-user-9", "identified");
        when(f.personal.verifiedOpenUserId(9L)).thenReturn("open-user-9");
        com.tradepass.framework.common.core.AuthContext.set(9L, 3L);
        try {
            assertThat(f.service.syncLegalRepresentative(3L).status()).isEqualTo("VERIFIED");
            verify(f.certifications).completeLegalClaim(3L, 9L, "FDD-LEGAL-local-3-9");
            assertThat(f.identity.getApplicantUserId()).isEqualTo(9L);
            assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
            verify(f.certifications).completeProviderCertification(eq(3L), eq(9L), eq("FDD-LEGAL-local-3-9"), anyString(), eq(CertifiedApplicantRole.LEGAL));
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void legalClaimDoesNotCallProviderForAnUnapprovedMemberOrOccupiedLegalRole() {
        var f = new Fixture();
        doThrow(new com.tradepass.framework.common.exception.BusinessException("请先通过企业成员邀请加入本企业"))
                .when(f.certifications).requireLegalClaim(3L, 9L);
        com.tradepass.framework.common.core.AuthContext.set(9L, 3L);
        try {
            assertThatThrownBy(() -> f.service.createLegalRepresentativeUrl(3L)).hasMessageContaining("成员邀请");
            assertThatThrownBy(() -> f.service.syncLegalRepresentative(3L)).hasMessageContaining("成员邀请");
            verifyNoInteractions(f.gateway);
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void recoversMissingProviderIdByCreditCodeAndStillVerifiesTheApplicant() {
        var f = new Fixture();
        f.detail("legal_rep", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032"));
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenReturn(
                new FadadaCompanyGateway.CompanyAccount("local-3", "corp-3", "authorized", "identified", "enable", SCOPES));
        assertThat(f.service.syncCurrent(3L).status()).isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.LEGAL));
    }

    @ParameterizedTest
    @CsvSource({"another-client,open-user-7", "local-3,another-user"})
    void recoveryCannotBindAnotherClientOrGrantAnotherOperatorsCompany(String client, String operator) {
        var f = new Fixture();
        f.detail("legal_rep", operator, "identified");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032"));
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenReturn(
                new FadadaCompanyGateway.CompanyAccount(client, "corp-3", "authorized", "identified", "enable", SCOPES));
        assertThat(f.service.syncCurrent(3L).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void rateLimitedCompanyQueryIsPersistedAndNextPollDoesNotHitProvider() {
        var f = new Fixture();
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("100020"));
        assertThat(f.service.syncCurrent(3L).failureReason()).contains("30秒");
        assertThat(f.service.syncCurrent(3L).status()).isEqualTo("IN_PROGRESS");
        verify(f.gateway, times(1)).getCompany(anyString(), any());
        verify(f.gateway, never()).getCompanyByCreditCode(anyString());
        verifyNoInteractions(f.certifications);
    }

    @Test
    void absentAuthorizationIsNotSuccessAndBothLookupsAreThrottled() {
        var f = new Fixture();
        var missing = new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(missing);
        when(f.gateway.getCompanyByCreditCode(anyString())).thenThrow(missing);
        assertThat(f.service.syncCurrent(3L).failureReason()).contains("授权记录");
        f.service.syncCurrent(3L);
        verify(f.gateway, times(1)).getCompany(anyString(), any());
        verify(f.gateway, times(1)).getCompanyByCreditCode("TEST-CREDIT");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void alreadyAuthorizedEnrollmentReconcilesInsteadOfGeneratingAnotherUrl() {
        var f = new Fixture();
        f.detail("deputy_auth", "open-user-7", "identified");
        when(f.gateway.createAuthUrl(any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210002"));
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.ADMIN));
        } finally { AuthContext.clear(); }
    }

    private static class Fixture {
        final FadadaCorpIdentityMapper identities = mock(FadadaCorpIdentityMapper.class);
        final FadadaCorpSealMapper seals = mock(FadadaCorpSealMapper.class);
        final CompanyMapper companies = mock(CompanyMapper.class);
        final CompanyCertificationService certifications = mock(CompanyCertificationService.class);
        final FadadaPersonalIdentityService personal = mock(FadadaPersonalIdentityService.class);
        final FadadaCompanyGateway gateway = mock(FadadaCompanyGateway.class);
        final FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO();
        final CompanyDO company = new CompanyDO();
        final FadadaCompanyService service;
        Fixture() {
            MybatisTestSupport.initialize(CompanyDO.class, FadadaCorpIdentityDO.class, com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpSealDO.class);
            company.setId(3L); company.setName("认证企业");
            company.setCreditCode("TEST-CREDIT"); company.setCertificationStatus("PENDING_REVIEW");
            identity.setId(5L); identity.setCompanyId(3L); identity.setApplicantUserId(7L);
            identity.setClientCorpId("local-3"); identity.setLocalStatus("IN_PROGRESS");
            when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
            when(companies.selectByIdForUpdate(3L)).thenReturn(company);
            when(personal.verifiedOpenUserId(7L)).thenReturn("open-user-7");
            when(gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                    "local-3", "corp-3", "authorized", "identified", "enable", SCOPES));
            var properties = new FadadaProperties();
            properties.setEnabled(true); properties.setAppId("test-app"); properties.setAppSecret("test-secret");
            properties.setServerUrl("https://example.test"); properties.setCallbackUrl("https://example.test/callback");
            service = new FadadaCompanyServiceImpl(identities, seals, companies, mock(AccessControlService.class),
                    certifications, personal, gateway, properties, new ObjectMapper());
        }
        void detail(String type, String operatorId, String status) {
            when(gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                    "corp-3", status, "认证企业", "TEST-CREDIT", "张三", "letter", null, null, type, operatorId));
        }
    }

    @Test void cachedProviderCertificationCannotAuthorizeChangedOrUnverifiedCompany() {
        MybatisTestSupport.initialize(FadadaCorpIdentityDO.class);
        var identities = mock(FadadaCorpIdentityMapper.class);
        var companies = mock(CompanyMapper.class);
        var service = new FadadaCompanyServiceImpl(identities, mock(FadadaCorpSealMapper.class), companies,
                mock(AccessControlService.class), mock(CompanyCertificationService.class),
                mock(FadadaPersonalIdentityService.class), mock(FadadaCompanyGateway.class),
                new FadadaProperties(), new ObjectMapper());
        CompanyDO company = new CompanyDO(); company.setId(3L); company.setName("认证企业");
        company.setCreditCode("TEST-CREDIT"); company.setCertificationStatus("VERIFIED");
        FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO(); identity.setLocalStatus("VERIFIED");
        identity.setOpenCorpId("corp-3"); identity.setVerifiedName("认证企业"); identity.setVerifiedCreditCode("TEST-CREDIT");
        identity.setAuthScopes("[\"ident_info\",\"seal_info\",\"signtask_init\",\"signtask_info\",\"signtask_file\"]");
        when(companies.selectById(3L)).thenReturn(company);
        when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
        assertThat(service.requireVerified(3L)).isSameAs(identity);
        company.setName("篡改名称");
        assertThatThrownBy(() -> service.requireVerified(3L)).hasMessageContaining("认证记录不一致");
        company.setName("认证企业"); company.setCertificationStatus("REJECTED");
        assertThatThrownBy(() -> service.requireVerified(3L)).hasMessage("请先完成企业认证");
    }
}
