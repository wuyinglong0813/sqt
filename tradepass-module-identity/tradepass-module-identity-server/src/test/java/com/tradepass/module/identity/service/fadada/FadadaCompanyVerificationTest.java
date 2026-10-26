package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpSealDO;
import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import com.tradepass.module.identity.service.company.CompanyOnboardingAttemptService;
import com.tradepass.module.identity.service.permission.AccessControlService;

import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpSealMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyOnboardingAttemptDO;
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
    @Test
    void corporateRevocationWinsOverSameTimeSuccessAndStaleAccountReadsThenQueriesRecover() {
        var f = new Fixture(); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-7");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now().minusYears(1));
        long eventTime = System.currentTimeMillis();
        var cancel = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-cancel-authorization")
                .put("eventTime", String.valueOf(eventTime));
        assertThat(f.service.syncCallback("local-3", "corp-3", cancel).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.gateway);
        var obsolete = f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")
                .put("eventTime", String.valueOf(eventTime)));
        assertThat(obsolete.status()).isEqualTo("IN_PROGRESS");
        assertThat(obsolete.callbackObsolete()).isTrue();
        assertThat(f.identity.getBindingStatus()).isEqualTo("unauthorized");
        assertThat(f.identity.getAuthorizationEventType()).isEqualTo("corp-cancel-authorization");
        verify(f.gateway, never()).getIdentity(anyString());
        verifyNoInteractions(f.certifications);
        f.identity.setAuthorizationRevokedAt(java.time.LocalDateTime.now().minusMinutes(1));
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
        assertThat(f.service.syncCallback("local-3", "corp-3", cancel).status()).isEqualTo("VERIFIED");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void trustedOwnerRevocationReachesTheExistingLocalIdentityWithoutReplacingItsClientId() {
        var f = new Fixture(); trustOwner(f);
        f.company.setCertificationStatus("VERIFIED");
        f.identity.setLocalStatus("VERIFIED"); f.identity.setOpenCorpId("corp-3");
        f.identity.setBindingStatus("authorized"); f.identity.setAuthScopes("[\"organization\"]");
        when(f.identities.selectOne(any(Wrapper.class))).thenReturn(null, f.identity, f.identity);
        var cancel = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-cancel-authorization")
                .put("clientCorpId", "default-owner-client").put("openCorpId", "corp-3")
                .put("eventTime", String.valueOf(System.currentTimeMillis()));

        var result = f.service.syncCallback("default-owner-client", "corp-3", cancel);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.companyAuthorizationReady()).isFalse();
        assertThat(f.identity.getBindingStatus()).isEqualTo("unauthorized");
        assertThat(f.identity.getAuthScopes()).isEqualTo("[]");
        assertThat(f.identity.getAuthorizationEventType()).isEqualTo("corp-cancel-authorization");
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        verify(f.identities).updateById(f.identity);
        verifyNoInteractions(f.gateway, f.certifications);
    }

    @Test
    void ownerCallbackCannotReachACompanyWhoseCreditCodeDoesNotMatchTheTrustedTuple() {
        var f = new Fixture(); trustOwner(f);
        f.properties.setOwnerEnterpriseCreditCode("OTHER-CREDIT");
        f.identity.setLocalStatus("VERIFIED"); f.identity.setOpenCorpId("corp-3");
        f.identity.setBindingStatus("authorized");
        when(f.identities.selectOne(any(Wrapper.class))).thenReturn(null, f.identity, f.identity);
        var cancel = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-cancel-authorization")
                .put("eventTime", String.valueOf(System.currentTimeMillis()));

        assertThat(f.service.syncCallback("default-owner-client", "corp-3", cancel)).isNull();

        assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
        assertThat(f.identity.getBindingStatus()).isEqualTo("authorized");
        assertThat(f.identity.getAuthorizationEventType()).isNull();
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verifyNoInteractions(f.gateway, f.certifications);
    }

    @Test
    void anOrdinaryWrongCallbackClientCannotFallBackToAnExistingOpenCorpId() {
        var f = new Fixture(); trustOwner(f);
        f.identity.setLocalStatus("VERIFIED"); f.identity.setOpenCorpId("corp-3");
        when(f.identities.selectOne(any(Wrapper.class))).thenReturn(null, f.identity);
        var cancel = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-cancel-authorization")
                .put("eventTime", String.valueOf(System.currentTimeMillis()));

        assertThat(f.service.syncCallback("wrong-client", "corp-3", cancel)).isNull();

        assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
        assertThat(f.identity.getAuthorizationEventType()).isNull();
        verify(f.identities, times(1)).selectOne(any(Wrapper.class));
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verifyNoInteractions(f.companies, f.gateway, f.certifications);
    }

    @Test
    void olderCorporateRevocationCannotOverrideNewerAuthorizationWatermark() {
        var f = new Fixture(); f.identity.setLocalStatus("VERIFIED");
        f.identity.setAuthorizationEventAt(java.time.LocalDateTime.now());
        var cancel = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-cancel-authorization")
                .put("eventTime", String.valueOf(System.currentTimeMillis() - 2000));
        var obsolete = f.service.syncCallback("local-3", "corp-3", cancel);
        assertThat(obsolete.status()).isEqualTo("VERIFIED");
        assertThat(obsolete.callbackObsolete()).isTrue();
        verifyNoInteractions(f.gateway, f.certifications);
    }

    @Test
    void duplicateCorporateSuccessPersistsItsAuthorizationWatermarkBeforeReturning() {
        var f = new Fixture(); f.identity.setLocalStatus("VERIFIED");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now().plusMinutes(1));
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        assertThat(f.identity.getAuthorizationEventAt()).isNotNull();
        assertThat(f.identity.getAuthorizationEventType()).isEqualTo("corp-authorize");
        verify(f.identities).updateById(f.identity);
        verifyNoInteractions(f.gateway);
    }

    @Test
    void establishedCompanyRenewalNeverReapprovesItsHistoricalApplicationEvenForTheSameOperator() {
        var f = new Fixture(); f.company.setCertificationStatus("VERIFIED");
        f.identity.setLocalStatus("VERIFIED"); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-7");
        // Legacy company records can be certified before their provider verified_at was stored.
        assertThat(f.identity.getVerifiedAt()).isNull();
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void changedAuthorizationOperatorDoesNotRewriteOrReapproveTheHistoricalApplicant() {
        var f = new Fixture(false);
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now().minusYears(1));
        f.identity.setApplicantUserId(99L); f.identity.setProviderRequestId("original-application-99");
        f.identity.setOperatorType("legal_rep"); f.identity.setOperatorId("open-user-99");
        f.documentedOperator("legal_rep", "open-user-99");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/organization-consent");
        AuthContext.set(7L, 3L);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isNotNull();
            assertThat(f.identity.getAuthorizationUserId()).isEqualTo(7L);
            f.superAdmin();
            assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                    .isEqualTo("VERIFIED");
            assertThat(f.identity.getApplicantUserId()).isEqualTo(99L);
            assertThat(f.identity.getProviderRequestId()).isEqualTo("original-application-99");
            assertThat(f.identity.getOperatorId()).isEqualTo("tradepass-user-7");
            assertThat(f.identity.getOperatorType()).isEqualTo("member_super_admin");
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void aDifferentPersonsAuthorizationCallbackCannotConfirmTheCurrentOperator() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-99");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setVerifiedAt(java.time.LocalDateTime.now().minusYears(1));
        f.identity.setApplicantUserId(99L); f.identity.setAuthorizationUserId(7L);
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-99")).status())
                .isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void resourceRefreshDoesNotDependOnTheFormerOperatorsMembershipAndRetriesSealFailures() {
        var f = new Fixture(false);
        var signingPreparation = mock(com.tradepass.module.identity.dal.mysql.fadada.FadadaSigningMemberSyncMapper.class);
        ((FadadaCompanyServiceImpl) f.service).setSigningPreparation(signingPreparation);
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now().minusYears(1));
        f.identity.setApplicantUserId(99L); f.documentedOperator("legal_rep", "open-user-99");
        when(f.gateway.listSeals("corp-3")).thenThrow(new BusinessException("temporary failure"))
                .thenReturn(List.of(new FadadaCompanyGateway.SealInfo("912", "合同章", "corp", "enable")));
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
        assertThat(f.identity.getSealSyncWarning()).contains("暂未同步");
        verifyNoInteractions(signingPreparation);
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
        assertThat(f.identity.getSealSyncWarning()).isEmpty();
        verify(signingPreparation).invalidateCompanySealProof(3L);
        when(f.gateway.listSeals("corp-3")).thenReturn(List.of(new FadadaCompanyGateway.SealInfo("913", "新合同章", "corp", "enable")));
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
        verify(signingPreparation, times(2)).invalidateCompanySealProof(3L);
        verifyNoInteractions(f.personal, f.certifications);
        verify(f.gateway, times(3)).listSeals("corp-3");
    }

    @Test
    void aVerifiedLegacyEnterpriseCanAddOrganizationConsentWithoutReplacingItsCertificationApplicant() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-99", "identified");
        f.identity.setApplicantUserId(99L);
        when(f.personal.verifiedOpenUserId(99L)).thenReturn("open-user-99");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/add-organization-consent");
        AuthContext.set(7L, 3L);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isEqualTo("https://example.test/add-organization-consent");
            verify(f.gateway).createAuthUrl(argThat(command -> command.authScopes().contains("organization")));
            assertThat(f.identity.getApplicantUserId()).isEqualTo(99L);
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            assertThat(f.service.current(3L).organizationAuthorized()).isFalse();
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void verifiedEnterpriseWithOrganizationConsentDoesNotStartAnotherAuthorization() {
        var f = new Fixture(); f.superAdmin(); f.detail("legal_rep", "open-user-7", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        AuthContext.set(7L, 3L);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isNull();
            verify(f.gateway, never()).createAuthUrl(any());
        } finally { AuthContext.clear(); }
    }

    @Test
    void aLegacySupplementaryAttemptReconcilesLiveBeforeOpeningAnotherProviderPage() {
        var f = new Fixture(); f.superAdmin(); f.detail("legal_rep", "open-user-7", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("IN_PROGRESS");
        f.identity.setLastSyncAt(java.time.LocalDateTime.now());
        AuthContext.set(7L, 3L);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(f.service.current(3L).organizationAuthorized()).isTrue();
            verify(f.gateway).getCompany(anyString(), any());
            verify(f.gateway, never()).createAuthUrl(any());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void organizationConsentIsReportedIndependentlyFromHistoricalEnterpriseVerification() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-7", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        assertThat(f.service.sync(3L).organizationAuthorized()).isFalse();
        f.superAdmin();
        var result = f.service.sync(3L);
        assertThat(result.organizationAuthorized()).isTrue();
        assertThat(result.withCallbackObsolete(true).organizationAuthorized()).isTrue();
        f.identity.setAuthScopes("{\"organization\":true}");
        assertThat(f.service.current(3L).organizationAuthorized()).isFalse();
    }

    @Test
    void alreadyAuthorizedResponseCannotPretendTheMissingOrganizationConsentWasGranted() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-7", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        when(f.gateway.createAuthUrl(any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210002"));
        AuthContext.set(7L, 3L);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("AUTHORIZATION_REQUIRED");
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            assertThat(f.service.current(3L).organizationAuthorized()).isFalse();
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void firstEnrollmentRetainsConfirmedIdentityWhenAlreadyAuthorizedStillLacksOrganizationConsent() {
        var f = new Fixture(); f.detail("deputy_auth", "open-user-7", "identified");
        when(f.gateway.createAuthUrl(any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210002"));
        AuthContext.set(7L, 3L);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("AUTHORIZATION_REQUIRED");
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
            assertThat(f.service.current(3L).organizationAuthorized()).isFalse();
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                    eq(CertifiedApplicantRole.ADMIN));
        } finally { AuthContext.clear(); }
    }

    @Test
    void supplementaryConsentStaysPendingUntilARealProviderRefreshConfirmsTheNewScope() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-99", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        f.identity.setApplicantUserId(99L); f.identity.setProviderRequestId("original-99");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/supplementary-consent");
        AuthContext.set(7L, 3L);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isEqualTo("https://example.test/supplementary-consent");
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(f.service.syncCurrent(3L).organizationAuthorized()).isFalse();
            f.superAdmin();
            var complete = f.service.syncCurrent(3L);
            assertThat(complete.status()).isEqualTo("VERIFIED");
            assertThat(complete.organizationAuthorized()).isTrue();
            assertThat(f.identity.getApplicantUserId()).isEqualTo(99L);
            assertThat(f.identity.getProviderRequestId()).isEqualTo("original-99");
            verifyNoInteractions(f.certifications);
            verify(f.gateway, times(3)).getCompany(anyString(), any());
            verify(f.gateway).createAuthUrl(any());
        } finally { AuthContext.clear(); }
    }

    @Test
    void supplementaryConsentFailurePreservesVerifiedCompanyAndItsOriginalApplication() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-99", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        f.identity.setApplicantUserId(99L); f.identity.setProviderRequestId("original-99");
        f.identity.setAuthorizationUserId(99L);
        when(f.gateway.createAuthUrl(any())).thenThrow(new BusinessException("暂时无法获取授权页面"));
        AuthContext.set(7L, 3L);
        try {
            assertThatThrownBy(() -> f.service.createAuthUrl(3L)).hasMessageContaining("暂时无法");
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            assertThat(f.identity.getApplicantUserId()).isEqualTo(99L);
            assertThat(f.identity.getAuthorizationUserId()).isEqualTo(99L);
            assertThat(f.identity.getAuthorizationSubmittedAt()).isNull();
            assertThat(f.identity.getProviderRequestId()).isEqualTo("original-99");
            assertThat(f.service.current(3L).organizationAuthorized()).isFalse();
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void supplementaryConsentRefreshCanRecoverWithoutRecreatingTheAuthorizationOrLosingIdentity() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-7", "identified");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/supplementary-consent");
        AuthContext.set(7L, 3L);
        try {
            f.service.createAuthUrl(3L);
            when(f.gateway.getCompany(anyString(), any())).thenThrow(
                    new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("100020"));
            assertThatThrownBy(() -> f.service.syncCurrent(3L)).hasMessageContaining("30秒");
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            assertThat(f.service.current(3L).organizationAuthorized()).isFalse();
            doReturn(new FadadaCompanyGateway.CompanyAccount("local-3", "corp-3", "authorized", "identified", "enable",
                    java.util.stream.Stream.concat(SCOPES.stream(), java.util.stream.Stream.of("organization")).toList()))
                    .when(f.gateway).getCompany(anyString(), any());
            assertThat(f.service.syncCurrent(3L).organizationAuthorized()).isTrue();
            assertThat(f.identity.getAuthorizationUserId()).isEqualTo(7L);
            verify(f.gateway).createAuthUrl(any());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void revokedLocalCompanyAccessCannotReuseAnExistingSupplementaryConsentAttempt() {
        var f = new Fixture(); f.identity.setLocalStatus("VERIFIED"); f.superAdmin();
        doThrow(new BusinessException("无权访问该企业")).when(f.accessControl).requireCertificationOperator(3L);
        AuthContext.set(7L, 3L);
        try {
            assertThatThrownBy(() -> f.service.current(3L)).hasMessageContaining("无权");
            assertThatThrownBy(() -> f.service.syncCurrent(3L)).hasMessageContaining("无权");
            assertThatThrownBy(() -> f.service.createAuthUrl(3L)).hasMessageContaining("无权");
            verifyNoInteractions(f.gateway, f.certifications, f.personal);
        } finally { AuthContext.clear(); }
    }
    @Test
    void historicalLocalLegalMarkerCannotReplaceMissingLiveCertificationOperatorEvidence() {
        var f = new Fixture(false); f.detailWithoutOperator("legal_rep");
        f.identity.setOperatorType("legal_rep"); f.identity.setOperatorId("open-user-7");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now()); f.identity.setIdentMethod("legal_rep");
        assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void documentedLegacyOperatorProvesTheActualLegalPersonWithoutAnOptInOrPaidVerification() {
        var f = new Fixture(false); f.superAdmin();
        f.documentedOperator("legal_rep", "open-user-7");
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                eq(CertifiedApplicantRole.LEGAL));
        assertThat(f.identity.getOperatorType()).isEqualTo("legal_rep");
        assertThat(f.identity.getOperatorId()).isEqualTo("open-user-7");
    }

    @ParameterizedTest
    @CsvSource({"deputy_auth,identified,open-user-7,VERIFIED", "unknown,identified,open-user-7,IN_PROGRESS",
            "legal_rep,ident_invalid,open-user-7,IN_PROGRESS", "legal_rep,identified,'',IN_PROGRESS"})
    void documentedSourceDoesNotOverrideTheActualOperatorTypeIdentityStatusOrMissingIdentifier(
            String type, String status, String operator, String expected) {
        var f = new Fixture(false); f.superAdmin();
        when(f.gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                "corp-3", status, "认证企业", "TEST-CREDIT", "张三", "legal_rep", null,
                "2024-01-01T10:00:00", type, operator, true));
        assertThat(f.service.sync(3L).status()).isEqualTo(expected);
        verify(f.certifications, never()).completeProviderCertification(anyLong(), anyLong(), anyString(), anyString(),
                eq(CertifiedApplicantRole.LEGAL));
        if ("VERIFIED".equals(expected)) {
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                    eq(CertifiedApplicantRole.ADMIN));
        }
    }

    @Test
    void anExistingAdministratorCanRecoverLegalIdentityFromTheDocumentedOriginalOperator() {
        var f = new Fixture(false); f.superAdmin();
        f.identity.setLocalStatus("VERIFIED"); f.identity.setOpenCorpId("corp-3");
        f.identity.setOperatorType("member_super_admin"); f.identity.setOperatorId("tradepass-user-7");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now()); f.identity.setIdentMethod("legal_rep");
        f.documentedOperator("legal_rep", "open-user-7");
        AuthContext.set(7L, 3L);
        try {
            assertThat(f.service.syncLegalRepresentative(3L).status()).isEqualTo("VERIFIED");
            verify(f.certifications).completeIndependentLegalClaim(eq(3L), eq(7L), matches("FDD-LEGAL-[a-f0-9]{32}"));
            assertThat(f.identity.getOperatorType()).isEqualTo("legal_rep");
        } finally { AuthContext.clear(); }
    }

    @Test
    void anotherOriginalLegalPersonDoesNotMakeTheCurrentAuthorizedSuperAdminLegalOrBlockAdministration() {
        var f = new Fixture(false); f.superAdmin();
        f.documentedOperator("legal_rep", "someone-elses-open-user");
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                eq(CertifiedApplicantRole.ADMIN));
        AuthContext.set(7L, 3L);
        try {
            assertThat(f.service.syncLegalRepresentative(3L).status()).isEqualTo("REVIEW_REQUIRED");
            verify(f.certifications, never()).completeIndependentLegalClaim(anyLong(), anyLong(), anyString());
        } finally { AuthContext.clear(); }
    }

    @Test
    void anExistingCompanyWithAnotherOriginalOperatorExplainsTheRequiredCurrentAccountAuthorization() {
        var f = new Fixture(false);
        f.documentedOperator("legal_rep", "someone-elses-open-user");
        var result = f.service.syncCurrent(3L);
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.failureReason()).contains("已在法大大实名", "原认证经办人不是当前账号",
                "原经办人不等于当前法人", "本人企业身份仍需独立核验", "联系平台支持核验")
                .doesNotContain("尚未返回经办人身份", "稍后重新同步");
        verifyNoInteractions(f.certifications);
        verify(f.gateway, never()).getMember(anyString(), anyLong());
        verify(f.gateway, never()).createAuthUrl(any());
    }

    @ParameterizedTest
    @CsvSource({"other-open-user,张三", "open-user-7,另一个人"})
    void documentedOperatorStillRequiresTheCurrentVerifiedPersonalBindingAndConsistentName(String openUserId, String name) {
        var f = new Fixture(false); f.superAdmin();
        f.documentedOperator("legal_rep", "open-user-7");
        when(f.personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson(
                openUserId, "fdd-user-7", name));
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                .isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void undocumentedSdkOperatorExtensionCannotGrantPermissionsWithoutProviderConfirmation() {
        var f = new Fixture(false); f.detail("legal_rep", "open-user-7", "identified");
        assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
        assertThat(new FadadaProperties().isOperatorOpenUserIdConfirmed()).isFalse();
    }

    @Test
    void successFromAnOlderCompanyAttemptCannotSupplyTheCurrentOperator() {
        var f = new Fixture(); f.detailWithoutOperator("legal_rep");
        var old = operatorCallback("legal_rep", "tradepass-user-7")
                .put("eventTime", String.valueOf(System.currentTimeMillis() - 120000));
        assertThat(f.service.syncCallback("local-3", "corp-3", old).status()).isEqualTo("IN_PROGRESS");
        verify(f.gateway).getCompany("local-3", "corp-3");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void olderAttemptsSuccessCannotCompleteTheReplacementApplicationEvenForTheSameApplicant() {
        var f = replacementApplicantFixture();
        f.detail("legal_rep", "open-user-9", "identified");
        var old = operatorCallback("legal_rep", "tradepass-user-9")
                .put("eventTime", String.valueOf(System.currentTimeMillis() - 120000));

        var result = f.service.syncCallback("local-3", "corp-3", old);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getApplicantUserId()).isEqualTo(9L);
        assertThat(f.identity.getProviderRequestId()).isEqualTo("replacement-application-9");
        assertThat(f.identity.getAuthorizationEventAt()).isNull();
        verifyNoInteractions(f.gateway, f.certifications);
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verify(f.companies, never()).update(any(Wrapper.class));
    }

    @Test
    void olderAttemptsFailureCannotRejectTheReplacementApplicationEvenForTheSameApplicant() {
        var f = replacementApplicantFixture();
        var old = operatorCallback("legal_rep", "tradepass-user-9")
                .put("eventTime", String.valueOf(System.currentTimeMillis() - 120000))
                .put("authResult", "fail").put("corpIdentProcessStatus", "failed")
                .put("corpIdentFailedReason", "原申请人的历史认证未通过");

        var result = f.service.syncCallback("local-3", "corp-3", old);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.company.getCertificationStatus()).isEqualTo("PENDING_REVIEW");
        assertThat(f.identity.getLocalStatus()).isEqualTo("IN_PROGRESS");
        assertThat(f.identity.getFailureReason()).isNull();
        assertThat(f.identity.getAuthorizationEventAt()).isNull();
        verifyNoInteractions(f.gateway, f.certifications);
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verify(f.companies, never()).update(any(Wrapper.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "fail"})
    void anotherApplicantsRecentlyDeliveredCallbackCannotChangeTheCurrentApplication(String authResult) {
        var f = replacementApplicantFixture();
        f.detail("legal_rep", "open-user-9", "identified");
        var anotherApplicant = operatorCallback("legal_rep", "tradepass-user-7")
                .put("authResult", authResult);
        if ("fail".equals(authResult)) {
            anotherApplicant.put("corpIdentProcessStatus", "failed").put("corpIdentFailedReason", "原申请失败");
        }

        var result = f.service.syncCallback("local-3", "corp-3", anotherApplicant);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getAuthorizationEventAt()).isNull();
        verifyNoInteractions(f.gateway, f.certifications);
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verify(f.companies, never()).update(any(Wrapper.class));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"success", "fail"})
    void anUnboundLegacyCallbackCannotBypassExistingOnboardingApplications(String authResult) {
        var f = replacementApplicantFixture();
        f.identity.setOnboardingAttemptId(null);
        f.identity.setApplicantUserId(7L);
        f.identity.setAuthorizationUserId(7L);
        f.identity.setProviderRequestId("expired-legacy-application-7");
        f.identity.setSubmittedAt(java.time.LocalDateTime.now().minusHours(2));
        f.identity.setAuthorizationSubmittedAt(f.identity.getSubmittedAt());
        when(f.attempts.current(3L)).thenReturn(null);
        f.detail("legal_rep", "open-user-7", "identified");
        var legacyCallback = operatorCallback("legal_rep", "tradepass-user-7")
                .put("authResult", authResult);
        if ("fail".equals(authResult)) {
            legacyCallback.put("corpIdentProcessStatus", "failed")
                    .put("corpIdentFailedReason", "过期旧申请的认证失败");
        }

        var result = f.service.syncCallback("local-3", "corp-3", legacyCallback);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getOnboardingAttemptId()).isNull();
        assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
        assertThat(f.identity.getAuthorizationEventAt()).isNull();
        assertThat(f.identity.getFailureReason()).isNull();
        verifyNoInteractions(f.gateway, f.certifications);
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verify(f.identities, never()).update(any(Wrapper.class));
        verify(f.companies, never()).update(any(Wrapper.class));
        verify(f.attempts, never()).complete(anyLong(), anyLong(), anyString());
    }

    @Test
    void callbackCannotReviveAnExpiredOrCancelledApplication() {
        var f = replacementApplicantFixture();
        when(f.attempts.current(3L)).thenReturn(null);
        var callback = operatorCallback("legal_rep", "tradepass-user-9");

        var result = f.service.syncCallback("local-3", "corp-3", callback);

        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getLocalStatus()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.gateway, f.certifications);
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verify(f.companies, never()).update(any(Wrapper.class));
    }

    @Test
    void aCurrentApplicantsCallbackCannotUseAnIdentityStillBoundToAnotherApplication() {
        var f = replacementApplicantFixture();
        f.identity.setOnboardingAttemptId(499L);
        var callback = operatorCallback("legal_rep", "tradepass-user-9");

        var result = f.service.syncCallback("local-3", "corp-3", callback);

        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getOnboardingAttemptId()).isEqualTo(499L);
        assertThat(f.identity.getAuthorizationEventAt()).isNull();
        verifyNoInteractions(f.gateway, f.certifications);
        verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        verify(f.companies, never()).update(any(Wrapper.class));
    }

    @Test
    void replacementApplicantCannotReuseTheFormerApplicantsLegalIdentity() {
        var f = replacementApplicantFixture();
        f.detail("legal_rep", "open-user-7", "identified");

        assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");

        assertThat(f.identity.getApplicantUserId()).isEqualTo(9L);
        assertThat(f.identity.getProviderRequestId()).isEqualTo("replacement-application-9");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void aFailureWithoutCurrentApplicantEvidenceOnlyRequeriesTheCurrentApplication() {
        var f = replacementApplicantFixture();
        f.detailWithoutOperator("legal_rep");
        var uncorrelatedFailure = companyCallback().put("authResult", "fail")
                .put("corpIdentProcessStatus", "failed").put("corpIdentFailedReason", "不属于当前申请的历史失败");

        var result = f.service.syncCallback("local-3", "corp-3", uncorrelatedFailure);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(f.identity.getFailureReason()).doesNotContain("不属于当前申请的历史失败");
        verify(f.gateway).getCompany("local-3", "corp-3");
        verify(f.gateway).getIdentity("corp-3");
        verifyNoInteractions(f.certifications);
        verify(f.companies, never()).update(argThat((Wrapper<CompanyDO> wrapper) ->
                wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> update
                        && update.getSqlSet().contains("certification_status")));
    }

    @Test
    void theFormerApplicantCannotStartOrSynchronizeTheReplacementApplicantsEnrollment() {
        var f = replacementApplicantFixture();
        doThrow(new BusinessException("其他申请人正在办理企业认证"))
                .when(f.attempts).activate(3L, 7L);
        doThrow(new BusinessException("本次企业认证申请已失效，请重新申请"))
                .when(f.attempts).requireCurrent(3L, 7L);
        AuthContext.set(7L, null);
        try {
            assertThatThrownBy(() -> f.service.createAuthUrl(3L)).hasMessageContaining("其他申请人");
            assertThatThrownBy(() -> f.service.syncCurrent(3L)).hasMessageContaining("已失效");

            assertThat(f.identity.getApplicantUserId()).isEqualTo(9L);
            assertThat(f.identity.getAuthorizationUserId()).isEqualTo(9L);
            assertThat(f.identity.getOnboardingAttemptId()).isEqualTo(501L);
            verifyNoInteractions(f.gateway, f.certifications);
            verify(f.identities, never()).updateById(any(FadadaCorpIdentityDO.class));
        } finally { AuthContext.clear(); }
    }

    @Test
    void replacementApplicantCompletesOnlyTheirOwnApplicationWithIndependentProviderIdentity() {
        var f = replacementApplicantFixture();
        f.company.setName("前一个申请人的错误企业名称");
        f.attempts.current(3L).setCompanyName("认证企业");
        f.detail("legal_rep", "open-user-9", "identified");

        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");

        verify(f.personal).verifiedOpenUserId(9L);
        verify(f.personal, never()).verifiedOpenUserId(7L);
        verify(f.certifications).completeProviderCertification(eq(3L), eq(9L),
                eq("replacement-application-9"), anyString(), eq(CertifiedApplicantRole.LEGAL));
        verify(f.attempts).complete(3L, 9L, "replacement-application-9");
        assertThat(f.company.getName()).isEqualTo("认证企业");
        verify(f.certifications, never()).completeProviderCertification(eq(3L), eq(7L),
                anyString(), anyString(), any());
    }

    @Test
    void establishedCompanyConsentRenewalCannotActivateAReplacementOnboardingApplication() {
        var f = new Fixture(false);
        ((FadadaCompanyServiceImpl) f.service).setOnboardingAttemptService(f.attempts);
        f.company.setCertificationStatus("VERIFIED");
        f.identity.setLocalStatus("VERIFIED");
        f.identity.setProviderRequestId("original-approved-application-7");
        f.identity.setOnboardingAttemptId(401L);
        f.documentedOperator("legal_rep", "open-user-7");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/renew-existing-company-consent");
        AuthContext.set(9L, 3L);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isEqualTo("https://example.test/renew-existing-company-consent");

            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
            assertThat(f.identity.getProviderRequestId()).isEqualTo("original-approved-application-7");
            assertThat(f.identity.getOnboardingAttemptId()).isEqualTo(401L);
            verify(f.attempts, never()).activate(anyLong(), anyLong());
            verify(f.attempts, never()).complete(anyLong(), anyLong(), anyString());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void completedEnrollmentThatReusedConsentStillProcessesCorporateRevocationWithoutAPageTimestamp() {
        var f = replacementApplicantFixture();
        f.company.setCertificationStatus("VERIFIED");
        f.identity.setLocalStatus("VERIFIED");
        f.identity.setOpenCorpId("corp-3");
        f.identity.setBindingStatus("authorized");
        f.identity.setAuthScopes("[\"ident_info\",\"organization\"]");
        f.identity.setSubmittedAt(null);
        f.identity.setAuthorizationSubmittedAt(null);
        f.identity.setVerifiedAt(java.time.LocalDateTime.now().minusYears(1));
        when(f.attempts.current(3L)).thenReturn(null);
        when(f.attempts.isEstablished(f.company)).thenReturn(true);
        var revocation = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-cancel-authorization")
                .put("eventTime", String.valueOf(System.currentTimeMillis()));

        var result = f.service.syncCallback("local-3", "corp-3", revocation);

        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.callbackObsolete()).isFalse();
        assertThat(f.identity.getBindingStatus()).isEqualTo("unauthorized");
        assertThat(f.identity.getAuthScopes()).isEqualTo("[]");
        assertThat(f.identity.getAuthorizationEventType()).isEqualTo("corp-cancel-authorization");
        assertThat(f.identity.getApplicantUserId()).isEqualTo(9L);
        verify(f.identities).updateById(f.identity);
        verifyNoInteractions(f.gateway, f.certifications);
    }

    @Test
    void establishedCompanyRecoversCertificationAfterFailedRenewalWithoutReapprovingAnApplicant() {
        var f = replacementApplicantFixture();
        f.company.setCertificationStatus("REJECTED");
        f.identity.setLocalStatus("FAILED");
        f.identity.setVerifiedAt(java.time.LocalDateTime.now().minusYears(1));
        when(f.attempts.current(3L)).thenReturn(null);
        when(f.attempts.isEstablished(f.company)).thenReturn(true);
        f.documentedOperator("legal_rep", "open-user-7");

        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");

        verify(f.companies).update(argThat((Wrapper<CompanyDO> wrapper) ->
                wrapper instanceof com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<?> update
                        && update.getSqlSet().contains("certification_status")
                        && update.getParamNameValuePairs().containsValue("VERIFIED")));
        verifyNoInteractions(f.personal, f.certifications);
        verify(f.attempts, never()).complete(anyLong(), anyLong(), anyString());
    }

    private static Fixture replacementApplicantFixture() {
        var f = new Fixture();
        f.identity.setApplicantUserId(9L);
        f.identity.setAuthorizationUserId(9L);
        f.identity.setOnboardingAttemptId(501L);
        f.identity.setProviderRequestId("replacement-application-9");
        var started = java.time.LocalDateTime.now().minusSeconds(30);
        f.identity.setSubmittedAt(started);
        f.identity.setAuthorizationSubmittedAt(started);
        var attempt = new CompanyOnboardingAttemptDO();
        attempt.setId(501L);
        attempt.setCompanyId(3L);
        attempt.setApplicantUserId(9L);
        attempt.setProviderRequestId("replacement-application-9");
        attempt.setStartedAt(started);
        attempt.setExpiresAt(started.plusDays(1));
        attempt.setStatus("ACTIVE");
        when(f.attempts.current(3L)).thenReturn(attempt);
        when(f.attempts.hasAttempts(3L)).thenReturn(true);
        when(f.attempts.ownsCurrent(3L, 9L)).thenReturn(true);
        when(f.attempts.hasPendingAttempt(3L, 9L)).thenReturn(true);
        ((FadadaCompanyServiceImpl) f.service).setOnboardingAttemptService(f.attempts);
        when(f.personal.verifiedOpenUserId(9L)).thenReturn("open-user-9");
        return f;
    }

    @Test
    void disabledOrIncompleteAuthorizationDoesNotQueryPrivateIdentityOrGrantMembership() {
        for (String state : List.of("disable", "", "unknown", "missing-scopes", "unauthorized")) {
            var f = new Fixture();
            when(f.gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                    "local-3", "corp-3", "unauthorized".equals(state) ? "unauthorized" : "authorized", "identified",
                    List.of("missing-scopes", "unauthorized").contains(state) ? "enable" : state,
                    "missing-scopes".equals(state) ? List.of() : SCOPES));
            assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");
            verify(f.gateway, never()).getIdentity(any());
            verifyNoInteractions(f.certifications);
        }
    }
    @ParameterizedTest
    @CsvSource({"legal_rep,LEGAL", "deputy_auth,ADMIN"})
    void completeCompanyCallbackSkipsAccountLookupButProvesOperatorRole(String type, CertifiedApplicantRole role) {
        var f = new Fixture(); f.detail(type, "open-user-7", "identified");
        f.identity.setLastSyncAt(java.time.LocalDateTime.now());
        f.identity.setFailureReason("尚未查询到当前企业的授权记录");
        assertThat(f.service.syncCallback("local-3", "corp-3", companyCallback()).status()).isEqualTo("VERIFIED");
        verify(f.gateway, never()).getCompany(any(), any());
        verify(f.gateway, never()).getCompanyByCreditCode(any());
        verify(f.gateway).getIdentity("corp-3");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(role));
    }

    @Test
    void companySuccessCallbackCannotGrantAnotherOperatorsPermissions() {
        var f = new Fixture(); f.detail("legal_rep", "another-user", "identified");
        assertThat(f.service.syncCallback("local-3", "corp-3", companyCallback()).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
        verify(f.gateway, never()).getCompany(any(), any());
    }

    @Test
    void incompleteCompanyCallbackFallsBackToAuthoritativeQuery() {
        for (String field : List.of("_verifiedEvent", "authResult", "corpIdentProcessStatus", "authScope",
                "openCorpId", "clientCorpId", "availableStatus", "eventTime", "corpIdentNo", "corpName")) {
            var f = new Fixture(); var callback = companyCallback(); callback.remove(field);
            when(f.gateway.getCompany(any(), any())).thenThrow(new BusinessException("query unavailable"));
            assertThatThrownBy(() -> f.service.syncCallback("local-3", "corp-3", callback)).hasMessage("query unavailable");
            verifyNoInteractions(f.certifications);
        }
    }

    @Test
    void cancelledCompanyCertificationIgnoresALaterSuccessCallback() {
        var f = new Fixture();
        f.company.setCertificationStatus("CANCELLED");
        f.identity.setLocalStatus("CANCELLED");
        assertThat(f.service.syncCallback("local-3", "corp-3", companyCallback()).status()).isEqualTo("CANCELLED");
        verifyNoInteractions(f.certifications, f.gateway);
    }

    @Test
    void companyCallbackCannotReplaceExistingProviderBinding() {
        var f = new Fixture(); f.identity.setOpenCorpId("another-corp");
        assertThatThrownBy(() -> f.service.syncCallback("local-3", "corp-3", companyCallback()))
                .hasMessageContaining("企业标识与当前企业不一致");
        verifyNoInteractions(f.gateway, f.certifications);
    }

    @Test
    void companyIdentityQueryFailureIsLeftForDurableCallbackRetry() {
        var f = new Fixture();
        when(f.gateway.getIdentity("corp-3")).thenThrow(new BusinessException("identity unavailable"));
        assertThatThrownBy(() -> f.service.syncCallback("local-3", "corp-3", companyCallback()))
                .hasMessage("identity unavailable");
        verifyNoInteractions(f.certifications);
        verify(f.gateway, never()).getCompany(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"legal_rep,ADMIN", "deputy_auth,ADMIN"})
    void currentSuperAdminMemberProvesManagementButHistoricalMethodDoesNotProveLegalIdentity(
            String method, CertifiedApplicantRole role) {
        var f = new Fixture(); f.detailWithoutOperator(method); f.superAdmin();
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback(method, "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(role));
        assertThat(f.identity.getOperatorType()).isEqualTo("member_super_admin");
        assertThat(f.identity.getOperatorId()).isEqualTo("tradepass-user-7");
        assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
    }

    @Test
    void callbackForAnotherOperatorAccountDoesNotActivateTheApplicant() {
        var f = new Fixture(); f.detailWithoutOperator("legal_rep");
        var result = f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-9"));
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.failureReason()).contains("本次授权经办人与当前小程序账号不一致", "企业授权不代表当前申请人的身份已通过",
                "本人账号完成企业身份核验");
        verifyNoInteractions(f.certifications);
        verify(f.gateway, never()).getMember(anyString(), anyLong());
    }

    @Test
    void existingCompanyAuthorizationCanOmitOptionalCertificationProcessButStillMustBeCurrentAndSigned() {
        for (boolean currentAndSigned : List.of(true, false)) {
            var f = new Fixture(); f.detailWithoutOperator("legal_rep"); f.superAdmin();
            f.identity.setSubmittedAt(java.time.LocalDateTime.now().minusMinutes(1));
            var callback = operatorCallback("legal_rep", "tradepass-user-7");
            callback.remove("corpIdentProcessStatus");
            if (!currentAndSigned) callback.remove("_verifiedEvent");
            var result = f.service.syncCallback("local-3", "corp-3", callback);
            assertThat(result.status()).isEqualTo(currentAndSigned ? "VERIFIED" : "IN_PROGRESS");
            verify(f.gateway).getCompany("local-3", "corp-3");
            if (currentAndSigned) {
                verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                        eq(CertifiedApplicantRole.ADMIN));
            } else verifyNoInteractions(f.certifications);
        }
    }

    @ParameterizedTest
    @CsvSource({"payment,payment", "offline,offline", "legal_rep,deputy_auth"})
    void callbackOperatorWithoutADocumentedRoleStaysPending(String queried, String notified) {
        var f = new Fixture(); f.detailWithoutOperator(queried);
        var result = f.service.syncCallback("local-3", "corp-3", operatorCallback(notified, "tradepass-user-7"));
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.failureReason()).isNotBlank();
        verifyNoInteractions(f.certifications);
    }

    @Test
    void storedCallbackOperatorDoesNotLetAnotherMemberClaimTheLegalRole() {
        var f = new Fixture(); f.detailWithoutOperator("legal_rep");
        f.superAdmin();
        f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7"));
        when(f.personal.verifiedOpenUserId(9L)).thenReturn("open-user-9");
        AuthContext.set(9L, 3L);
        try {
            assertThat(f.service.syncLegalRepresentative(3L).status()).isEqualTo("IN_PROGRESS");
            verify(f.certifications, never()).completeIndependentLegalClaim(anyLong(), anyLong(), anyString());
        } finally { AuthContext.clear(); }
    }

    @Test
    void loginMappingAloneNeverProvesManagementAuthority() {
        var f = new Fixture(); f.detailWithoutOperator("legal_rep");
        var callback = operatorCallback("legal_rep", "tradepass-user-7"); callback.remove("memberId");
        assertThat(f.service.syncCallback("local-3", "corp-3", callback).status()).isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
        verify(f.gateway, never()).getMember(anyString(), anyLong());
    }

    @ParameterizedTest
    @CsvSource({"inactive,super_admin,fdd-user-7,88", "disabled,super_admin,fdd-user-7,88",
            "activated,ordinary,fdd-user-7,88", "activated,super_admin,another-person,88",
            "activated,super_admin,fdd-user-7,99"})
    void mustMatchTheCurrentActiveSuperAdminMemberToTheVerifiedPerson(
            String status, String role, String person, long memberId) {
        var f = new Fixture(); f.detailWithoutOperator("payment"); f.superAdmin();
        when(f.gateway.getMember("corp-3", 88L)).thenReturn(new FadadaCompanyGateway.CompanyMember(
                memberId, status, List.of(role), person));
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("payment", "tradepass-user-7")).status())
                .isEqualTo("IN_PROGRESS");
        verifyNoInteractions(f.certifications);
    }

    @ParameterizedTest
    @CsvSource({"payment", "offline"})
    void documentedSuperAdminCanAuthorizeRegardlessOfTheHistoricalCertificationMethod(String method) {
        var f = new Fixture(); f.detailWithoutOperator(method); f.superAdmin();
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback(method, "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                eq(CertifiedApplicantRole.ADMIN));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode operatorCallback(String method, String operator) {
        var data = companyCallback().put("corpIdentMethod", method).put("memberId", "88");
        data.withArray("authScope").add("organization");
        data.putArray("clientUserIds").add(operator);
        return data;
    }

    private com.fasterxml.jackson.databind.node.ObjectNode companyCallback() {
        var data = new ObjectMapper().createObjectNode().put("_verifiedEvent", "corp-authorize")
                .put("clientCorpId", "local-3").put("openCorpId", "corp-3")
                .put("authResult", "success").put("corpIdentProcessStatus", "success")
                .put("availableStatus", "enable").put("eventTime", String.valueOf(System.currentTimeMillis()))
                .put("corpName", "认证企业").put("corpIdentNo", "TEST-CREDIT");
        var scopes = data.putArray("authScope"); SCOPES.forEach(scopes::add);
        return data;
    }

    private static final List<String> SCOPES = List.of("ident_info", "seal_info", "signtask_init", "signtask_info", "signtask_file");

    @Test
    void renewingAuthorizationDoesNotTurnAnActiveCompanyBackIntoAnUnapprovedApplication() {
        var f = new Fixture(); f.detail("deputy_auth", "open-user-7", "identified");
        f.company.setCertificationStatus("VERIFIED");
        var properties = new FadadaProperties();
        properties.setEnabled(true); properties.setAppId("test-app"); properties.setAppSecret("test-secret");
        properties.setServerUrl("https://example.test"); properties.setCallbackUrl("https://example.test/callback");
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/auth");
        com.tradepass.framework.common.core.AuthContext.set(7L, 3L);
        try {
            var service = new FadadaCompanyServiceImpl(f.identities, f.seals, f.companies, f.users, mock(AccessControlService.class),
                    f.certifications, f.personal, f.gateway, properties, new ObjectMapper());
            service.createAuthUrl(3L);
            verify(f.gateway).createAuthUrl(argThat(command ->
                    java.net.URLDecoder.decode(command.redirectMiniAppUrl(), java.nio.charset.StandardCharsets.UTF_8)
                            .equals("/pages/service-return/service-return?scene=company&companyId=3")));
            assertThat(f.company.getCertificationStatus()).isEqualTo("VERIFIED");
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            verifyNoInteractions(f.certifications);
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void companyAuthorizationPrefillsTheCurrentUsersBoundPhoneInsteadOfCompanyContactOrPastApplicant() {
        var f = new Fixture();
        f.company.setContactPhone("13900139000");
        f.identity.setApplicantUserId(99L);
        var currentUser = new SysUserDO(); currentUser.setId(7L); currentUser.setPhone("13800138000");
        when(f.users.selectById(7L)).thenReturn(currentUser);
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/auth");
        AuthContext.set(7L, 3L);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isEqualTo("https://example.test/auth");
            verify(f.gateway).createAuthUrl(argThat(command -> "tradepass-user-7".equals(command.clientUserId())
                    && "13800138000".equals(command.accountName())));
            verify(f.users).selectById(7L);
            verify(f.users, never()).selectById(99L);
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
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
    void existingAuthorizationCallbackUsesFreshProviderAndApplicantEvidence() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-7", "identified");
        assertThat(f.service.syncCallback("local-3", "corp-3", new ObjectMapper().createObjectNode()
                .put("authResult", "fail").put("authFailedReason", "exist")).status()).isEqualTo("VERIFIED");
        verify(f.gateway).getCompany("local-3", "corp-3");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                eq(CertifiedApplicantRole.LEGAL));
    }

    @Test
    void existingAuthorizationCannotUseAFailedCallbacksOperatorAsProof() {
        var f = new Fixture(); f.detailWithoutOperator("legal_rep");
        f.company.setCertificationStatus("REJECTED");
        var callback = operatorCallback("legal_rep", "tradepass-user-7")
                .put("authResult", "fail").put("authFailedReason", "exist");
        assertThat(f.service.syncCallback("local-3", "corp-3", callback).status()).isEqualTo("IN_PROGRESS");
        assertThat(f.identity.getFailureReason()).contains("尚未返回经办人身份");
        assertThat(f.company.getCertificationStatus()).isEqualTo("PENDING_REVIEW");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void existingAuthorizationAdoptsTheAuthoritativeClientBeforeIndependentlyVerifyingMembership() {
        var f = new Fixture(); f.detail("legal_rep", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                "older-client", "corp-3", "authorized", "identified", "enable", SCOPES));
        var result = f.service.syncCallback("local-3", "corp-3", new ObjectMapper().createObjectNode()
                .put("authResult", "fail").put("authFailedReason", "exist"));
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(f.identity.getClientCorpId()).isEqualTo("older-client");
        verify(f.gateway).getCompany(eq("local-3"), any());
        verify(f.gateway).getIdentity("corp-3");
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.LEGAL));
    }

    @Test
    void existingAuthorizationQueryFailureRemainsEligibleForDurableCallbackRetry() {
        var f = new Fixture();
        when(f.gateway.getCompany(anyString(), any())).thenThrow(new BusinessException("query unavailable"));
        assertThatThrownBy(() -> f.service.syncCallback("local-3", "corp-3", new ObjectMapper().createObjectNode()
                .put("authResult", "fail").put("authFailedReason", "exist"))).hasMessage("query unavailable");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void historicalExistFailureDisplaysPendingAndCanReconcileWithoutAMigration() {
        var f = new Fixture(); f.detailWithoutOperator("legal_rep");
        f.identity.setLocalStatus("FAILED"); f.identity.setFailureReason("exist");
        f.company.setCertificationStatus("REJECTED");
        assertThat(f.service.current(3L).status()).isEqualTo("IN_PROGRESS");
        assertThat(f.service.current(3L).failureReason()).contains("已有授权关联");
        assertThat(f.service.syncCurrent(3L).status()).isEqualTo("IN_PROGRESS");
        assertThat(f.identity.getLocalStatus()).isEqualTo("IN_PROGRESS");
        assertThat(f.company.getCertificationStatus()).isEqualTo("PENDING_REVIEW");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void actualRejectionAndFailedCertificationAreStillFailures() {
        for (boolean failedCertification : List.of(false, true)) {
            var f = new Fixture();
            var callback = new ObjectMapper().createObjectNode().put("authResult", "fail")
                    .put("authFailedReason", failedCertification ? "exist" : "reject");
            if (failedCertification) callback.put("corpIdentProcessStatus", "failed");
            assertThat(f.service.syncCallback("local-3", null, callback).status()).isEqualTo("FAILED");
            verifyNoInteractions(f.gateway, f.certifications);
        }
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
            var result = f.service.syncLegalRepresentative(3L);
            assertThat(result.status()).isEqualTo("deputy_auth".equals(type) ? "REVIEW_REQUIRED" : "IN_PROGRESS");
            verify(f.certifications, never()).completeIndependentLegalClaim(anyLong(), anyLong(), anyString());
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void verifiedSuperAdminNeedsSupplementalLegalEvidenceInsteadOfBeingToldToRepeatFaceVerification() {
        var f = new Fixture(false);
        f.detailWithoutOperator("legal_rep");
        f.superAdmin();
        assertThat(f.service.syncCallback("local-3", "corp-3", operatorCallback("legal_rep", "tradepass-user-7")).status())
                .isEqualTo("VERIFIED");
        assertThat(f.identity.getOperatorType()).isEqualTo("member_super_admin");
        AuthContext.set(7L, 3L);
        try {
            var result = f.service.syncLegalRepresentative(3L);
            assertThat(result.status()).isEqualTo("REVIEW_REQUIRED");
            assertThat(result.message()).contains("企业管理身份已确认", "尚无可确认", "联系平台支持")
                    .doesNotContain("当前认证记录为授权经办人", "选择法人本人认证");
            verify(f.certifications, never()).completeIndependentLegalClaim(anyLong(), anyLong(), anyString());
            verify(f.gateway, never()).createAuthUrl(any());
            verify(f.gateway, never()).createIdentityChangeUrl(any(), any(), any());
            assertThat(f.identity.getLocalStatus()).isEqualTo("VERIFIED");
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
        } finally { AuthContext.clear(); }
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
            verify(f.certifications).completeIndependentLegalClaim(eq(3L), eq(9L), matches("FDD-LEGAL-[a-f0-9]{32}"));
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
            assertThat(f.identity.getAuthorizationUserId()).isEqualTo(9L);
            assertThat(f.service.sync(3L).status()).isEqualTo("VERIFIED");
            verify(f.certifications, never()).completeProviderCertification(anyLong(), anyLong(), anyString(), anyString(), any());
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void legalClaimDoesNotCallProviderWhenAnotherLegalRepresentativeAlreadyExists() {
        var f = new Fixture();
        doThrow(new com.tradepass.framework.common.exception.BusinessException("本企业已有法人，请办理法人变更"))
                .when(f.certifications).requireLegalClaim(3L, 9L);
        com.tradepass.framework.common.core.AuthContext.set(9L, 3L);
        try {
            assertThatThrownBy(() -> f.service.createLegalRepresentativeUrl(3L)).hasMessageContaining("已有法人");
            assertThatThrownBy(() -> f.service.syncLegalRepresentative(3L)).hasMessageContaining("已有法人");
            verifyNoInteractions(f.gateway);
        } finally { com.tradepass.framework.common.core.AuthContext.clear(); }
    }

    @Test
    void aVerifiedLegalPersonWithoutMembershipCanClaimUsingTheirOwnToken() {
        var f = new Fixture(false);
        f.company.setCertificationStatus("VERIFIED");
        f.identity.setLocalStatus("VERIFIED"); f.identity.setOpenCorpId("corp-3");
        f.documentedOperator("legal_rep", "open-user-9");
        when(f.personal.verifiedOpenUserId(9L)).thenReturn("open-user-9");
        when(f.personal.verifiedPerson(9L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson(
                "open-user-9", "person-9", "张三"));
        AuthContext.set(9L, null);
        try {
            var result = f.service.syncLegalRepresentative(3L);
            assertThat(result.status()).isEqualTo("VERIFIED");
            var order = inOrder(f.personal, f.gateway, f.certifications);
            order.verify(f.certifications).requireLegalClaim(3L, 9L);
            order.verify(f.personal).requireCurrentVerified();
            order.verify(f.gateway).getCompany("local-3", "corp-3");
            order.verify(f.gateway).getIdentity("corp-3");
            order.verify(f.personal).verifiedOpenUserId(9L);
            order.verify(f.personal).verifiedPerson(9L);
            order.verify(f.certifications).completeIndependentLegalClaim(eq(3L), eq(9L), matches("FDD-LEGAL-[a-f0-9]{32}"));
            assertThat(f.identity.getAuthorizationUserId()).isEqualTo(9L);
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
            verifyNoInteractions(f.accessControl);
            verify(f.certifications, never()).completeLegalClaim(anyLong(), anyLong(), anyString());
        } finally { AuthContext.clear(); }
    }

    @Test
    void anUnverifiedNonMemberCannotReachEnterpriseProviderQueriesOrObtainMembership() {
        var f = new Fixture();
        doThrow(new BusinessException("请先完成个人实名认证")).when(f.personal).requireCurrentVerified();
        AuthContext.set(9L, null);
        try {
            assertThatThrownBy(() -> f.service.syncLegalRepresentative(3L)).hasMessageContaining("个人实名");
            verifyNoInteractions(f.gateway);
            verify(f.certifications, never()).completeIndependentLegalClaim(anyLong(), anyLong(), anyString());
            assertThat(f.identity.getAuthorizationUserId()).isNull();
        } finally { AuthContext.clear(); }
    }

    @Test
    void mobileLegalClaimNeverOpensThePcOnlyBusinessInformationChangePage() {
        var f = new Fixture();
        f.company.setCertificationStatus("VERIFIED");
        when(f.companies.selectById(3L)).thenReturn(f.company);
        f.identity.setLocalStatus("VERIFIED"); f.identity.setOpenCorpId("corp-3");
        f.identity.setVerifiedName("认证企业"); f.identity.setVerifiedCreditCode("TEST-CREDIT");
        f.identity.setAuthScopes("[\"ident_info\",\"seal_info\",\"signtask_init\",\"signtask_info\",\"signtask_file\"]");
        f.detailWithoutOperator("legal_rep");
        com.tradepass.framework.common.core.AuthContext.set(7L, 3L);
        try {
            assertThatThrownBy(() -> f.service.createLegalRepresentativeUrl(3L)).hasMessageContaining("尚未返回经办人身份");
            verify(f.gateway, never()).createIdentityChangeUrl(any(), any(), any());
            verify(f.certifications, never()).completeIndependentLegalClaim(anyLong(), anyLong(), anyString());
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
    void recoveryMayAdoptTheSameEnterpriseClientButStillRequiresTheCurrentOperator(String client, String operator) {
        var f = new Fixture();
        f.detail("legal_rep", operator, "identified");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032"));
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenReturn(
                new FadadaCompanyGateway.CompanyAccount(client, "corp-3", "authorized", "identified", "enable", SCOPES));
        var result = f.service.syncCurrent(3L);
        assertThat(f.identity.getClientCorpId()).isEqualTo(client);
        if ("open-user-7".equals(operator)) {
            assertThat(result.status()).isEqualTo("VERIFIED");
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.LEGAL));
        } else {
            assertThat(result.status()).isEqualTo("IN_PROGRESS");
            verifyNoInteractions(f.certifications);
        }
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
        var f = new Fixture(); f.superAdmin();
        f.detail("deputy_auth", "open-user-7", "identified");
        when(f.gateway.createAuthUrl(any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210002"));
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            verify(f.gateway, never()).createAuthUrl(any());
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.ADMIN));
        } finally { AuthContext.clear(); }
    }

    @Test
    void firstEnrollmentRecoversDefaultEnterpriseConsentAndVerifiesTheActualLegalOperatorBeforeOpeningAnyPage() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-7");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032"));
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenReturn(
                new FadadaCompanyGateway.CompanyAccount(null, "corp-3", "authorized", "identified", "enable", onboardingScopes()));
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
            verify(f.gateway).getCompanyByCreditCode("TEST-CREDIT");
            verify(f.gateway, never()).createAuthUrl(any());
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                    eq(CertifiedApplicantRole.LEGAL));
        } finally { AuthContext.clear(); }
    }

    @Test
    void defaultEnterpriseConsentDoesNotTurnThePurchaserOrAMatchingNameIntoTheCurrentApplicantsLegalProof() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("deputy_auth", "purchasers-open-user");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(
                new FadadaCompanyGateway.CompanyAccount(null, "corp-3", "authorized", "identified", "enable", onboardingScopes()));
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("IDENTITY_VERIFICATION_REQUIRED");
            assertThat(f.identity.getLocalStatus()).isEqualTo("IN_PROGRESS");
            assertThat(f.identity.getBindingStatus()).isEqualTo("authorized");
            assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(f.identity.getFailureReason()).contains("有效授权", "本人", "无需重复办理企业授权");
            verify(f.gateway, never()).createAuthUrl(any());
            verifyNoInteractions(f.certifications);

            // A later authoritative personal/operator result can complete the same saved application.
            f.documentedOperator("legal_rep", "open-user-7");
            var retry = f.service.createAuthUrl(3L);
            assertThat(retry.url()).isNull();
            assertThat(retry.status()).isEqualTo("VERIFIED");
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                    eq(CertifiedApplicantRole.LEGAL));
        } finally { AuthContext.clear(); }
    }

    @Test
    void anExistingFullAuthorizationAdoptsTheVerifiedProviderAssociationWithoutOwnerConfiguration() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-7");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032"));
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenReturn(
                new FadadaCompanyGateway.CompanyAccount("previous-client", "corp-3", "authorized", "identified", "enable", onboardingScopes()));
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(f.identity.getClientCorpId()).isEqualTo("previous-client");
            assertThat(f.identity.getCompanyId()).isEqualTo(3L);
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
            verify(f.identities).adoptProviderBinding(5L, 3L, "local-3", "previous-client", "corp-3");
            verify(f.gateway, never()).createAuthUrl(any());
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.LEGAL));
        } finally { AuthContext.clear(); }
    }

    @Test
    void organizationConsentIsRequestedUsingTheCanonicalClientAfterItsLiveIdentityWasVerified() {
        var f = new Fixture(false); f.detailWithoutOperator("legal_rep");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                "existing-provider-client", "corp-3", "authorized", "identified", "enable", SCOPES));
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/supplement-organization");
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isEqualTo("https://example.test/supplement-organization");
            assertThat(result.status()).isEqualTo("IN_PROGRESS");
            assertThat(f.identity.getClientCorpId()).isEqualTo("existing-provider-client");
            assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
            var ordered = inOrder(f.gateway, f.identities);
            ordered.verify(f.gateway).getCompany(eq("local-3"), any());
            ordered.verify(f.gateway).getIdentity("corp-3");
            ordered.verify(f.identities).adoptProviderBinding(5L, 3L, "local-3", "existing-provider-client", "corp-3");
            ordered.verify(f.gateway).createAuthUrl(argThat(command ->
                    "existing-provider-client".equals(command.clientCorpId()) && command.authScopes().contains("organization")));
            verify(f.gateway, never()).createAuthUrl(argThat(command -> "local-3".equals(command.clientCorpId())));
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void anExplicitlyTrustedApplicationOwnerCanReuseItsDefaultClientBindingButStillNeedsTheActualLegalPerson() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-7");
        trustOwner(f);
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032"));
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenReturn(ownerAccount());
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(f.identity.getClientCorpId()).isEqualTo("default-owner-client");
            verify(f.gateway, never()).createAuthUrl(any());
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(),
                    eq(CertifiedApplicantRole.LEGAL));
        } finally { AuthContext.clear(); }
    }

    @Test
    void aTrustedOwnerMappingIsNeverTheCurrentApplicantsPersonalLegalEvidence() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("deputy_auth", "purchasers-open-user");
        trustOwner(f);
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("IDENTITY_VERIFICATION_REQUIRED");
            assertThat(f.identity.getClientCorpId()).isEqualTo("default-owner-client");
            assertThat(f.identity.getLocalStatus()).isEqualTo("IN_PROGRESS");
            assertThat(f.identity.getFailureReason()).contains("有效授权", "本人", "无需重复办理企业授权");
            verify(f.gateway, never()).createAuthUrl(any());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"app", "openCorpId", "creditCode", "clientCorpId", "incomplete"})
    void ownerTupleConfigurationDoesNotGateAnIndependentlyVerifiedProviderAssociation(String mismatch) {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("legal_rep", "open-user-7");
        trustOwner(f);
        switch (mismatch) {
            case "app" -> f.properties.setOwnerEnterpriseAppId("different-app");
            case "openCorpId" -> f.properties.setOwnerEnterpriseOpenCorpId("different-corp");
            case "creditCode" -> f.properties.setOwnerEnterpriseCreditCode("different-credit");
            case "clientCorpId" -> f.properties.setOwnerEnterpriseClientCorpId("different-client");
            default -> f.properties.setOwnerEnterpriseAppId("");
        }
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("VERIFIED");
            assertThat(f.identity.getClientCorpId()).isEqualTo("default-owner-client");
            verify(f.gateway, never()).createAuthUrl(any());
            verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.LEGAL));
        } finally { AuthContext.clear(); }
    }

    @Test
    void authoritativeBindingIsPersistedEvenWhileTheCurrentApplicantsRoleRemainsUnproven() {
        var f = new Fixture(false); f.detailWithoutOperator("legal_rep");
        f.identity.setOpenCorpId("corp-3"); f.identity.setProviderRequestId("current-application-7");
        f.identity.setOnboardingAttemptId(501L);
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.status()).isEqualTo("IDENTITY_VERIFICATION_REQUIRED");
            assertThat(result.url()).isNull();
            assertThat(f.identity.getClientCorpId()).isEqualTo("default-owner-client");
            assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
            assertThat(f.identity.getCompanyId()).isEqualTo(3L);
            assertThat(f.identity.getApplicantUserId()).isEqualTo(7L);
            assertThat(f.identity.getProviderRequestId()).isEqualTo("current-application-7");
            assertThat(f.identity.getOnboardingAttemptId()).isEqualTo(501L);
            verify(f.identities).adoptProviderBinding(5L, 3L, "local-3", "default-owner-client", "corp-3");
            verifyNoInteractions(f.certifications);
            verify(f.gateway, never()).createAuthUrl(any());
        } finally { AuthContext.clear(); }
    }

    @ParameterizedTest
    @CsvSource({"unauthorized,identified,enable,true", "authorized,unidentified,enable,true",
            "authorized,identified,disable,true", "authorized,identified,enable,false"})
    void anUnreadyAccountCannotReplaceTheStoredClientBinding(String binding, String ident, String available, boolean fullScopes) {
        var f = new Fixture(); f.identity.setOpenCorpId("corp-3");
        f.detail("legal_rep", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                "queried-client", "corp-3", binding, ident, available, fullScopes ? onboardingScopes() : List.of("ident_info")));
        assertThat(f.service.sync(3L).status()).isEqualTo("IN_PROGRESS");
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
        verify(f.identities, never()).adoptProviderBinding(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(f.certifications);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"name", "creditCode"})
    void authoritativeQueryCannotAdoptABindingForAnotherCompany(String mismatch) {
        var f = new Fixture(); f.identity.setOpenCorpId("corp-3");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        when(f.gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                "corp-3", "identified", "name".equals(mismatch) ? "另一家企业" : "认证企业",
                "creditCode".equals(mismatch) ? "OTHER-CREDIT" : "TEST-CREDIT", "张三", "legal_rep", null,
                "2024-01-01T10:00:00", "legal_rep", "open-user-7", true));
        assertThatThrownBy(() -> f.service.sync(3L)).isInstanceOf(BusinessException.class);
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
        verify(f.identities, never()).adoptProviderBinding(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(f.certifications);
    }

    @Test
    void authoritativeQueryCannotReplaceAnAlreadyKnownOpenCorpId() {
        var f = new Fixture(); f.identity.setOpenCorpId("already-known-corp");
        f.detail("legal_rep", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        assertThatThrownBy(() -> f.service.sync(3L)).isInstanceOf(BusinessException.class);
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        assertThat(f.identity.getOpenCorpId()).isEqualTo("already-known-corp");
        verify(f.identities, never()).adoptProviderBinding(anyLong(), anyLong(), anyString(), anyString(), anyString());
        verifyNoInteractions(f.certifications);
    }

    @Test
    void aBindingAlreadyClaimedByAnotherLocalCompanyDoesNotChangeMemoryOrGrantMembership() {
        var f = new Fixture(); f.identity.setOpenCorpId("corp-3");
        f.detail("legal_rep", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        when(f.identities.adoptProviderBinding(5L, 3L, "local-3", "default-owner-client", "corp-3"))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("unique binding collision"));
        assertThatThrownBy(() -> f.service.sync(3L)).isInstanceOf(BusinessException.class);
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        assertThat(f.identity.getOpenCorpId()).isEqualTo("corp-3");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void aConcurrentBindingChangeCannotBeReportedAsAnAdoptedAssociation() {
        var f = new Fixture(); f.identity.setOpenCorpId("corp-3");
        f.detail("legal_rep", "open-user-7", "identified");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        when(f.identities.adoptProviderBinding(5L, 3L, "local-3", "default-owner-client", "corp-3")).thenReturn(0);
        assertThatThrownBy(() -> f.service.sync(3L)).isInstanceOf(BusinessException.class);
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        verifyNoInteractions(f.certifications);
    }

    @Test
    void aCompleteCallbackSnapshotDoesNotAdoptAnUnqueriedClientAssociation() {
        var f = new Fixture(); f.identity.setOpenCorpId("corp-3");
        f.detail("legal_rep", "open-user-7", "identified");
        // The provider query would report a different client, but this complete callback skips it.
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        assertThat(f.service.syncCallback("local-3", "corp-3", companyCallback()).status()).isEqualTo("VERIFIED");
        assertThat(f.identity.getClientCorpId()).isEqualTo("local-3");
        verify(f.gateway, never()).getCompany(anyString(), any());
        verify(f.identities, never()).adoptProviderBinding(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void theAdoptedCurrentClientCallbackStillUsesItsCurrentOperatorEvidence() {
        var f = new Fixture(false); f.superAdmin(); f.detailWithoutOperator("legal_rep");
        f.identity.setClientCorpId("default-owner-client"); f.identity.setOpenCorpId("corp-3");
        var callback = operatorCallback("legal_rep", "tradepass-user-7").put("clientCorpId", "default-owner-client");
        assertThat(f.service.syncCallback("default-owner-client", "corp-3", callback).status()).isEqualTo("VERIFIED");
        assertThat(f.identity.getClientCorpId()).isEqualTo("default-owner-client");
        verify(f.gateway).getMember("corp-3", 88L);
        verify(f.certifications).completeProviderCertification(eq(3L), eq(7L), anyString(), anyString(), eq(CertifiedApplicantRole.ADMIN));
        verify(f.gateway, never()).getCompany(anyString(), any());
        verify(f.identities, never()).adoptProviderBinding(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void theOldGeneratedAliasOnlyRequeriesAndCannotCarryAnotherPersonsOperatorProof() {
        var f = new Fixture(false); f.superAdmin(); f.detailWithoutOperator("legal_rep");
        f.identity.setClientCorpId("default-owner-client"); f.identity.setOpenCorpId("corp-3");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        onlyOldGeneratedAliasLookupMisses(f);
        var callback = operatorCallback("legal_rep", "tradepass-user-7").put("clientCorpId", "tradepass-company-3");
        var result = f.service.syncCallback("tradepass-company-3", "corp-3", callback);
        assertThat(result.status()).isEqualTo("IN_PROGRESS");
        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getClientCorpId()).isEqualTo("default-owner-client");
        assertThat(f.identity.getAuthorizationEventType()).isNull();
        verify(f.gateway).getCompany("default-owner-client", "corp-3");
        verify(f.gateway).getIdentity("corp-3");
        verify(f.gateway, never()).getMember(anyString(), anyLong());
        verifyNoInteractions(f.certifications);
    }

    @Test
    void revocationSentToTheOldGeneratedAliasCannotDirectlyRevokeTheCurrentAssociation() {
        var f = new Fixture(false); f.superAdmin(); f.detailWithoutOperator("legal_rep");
        f.company.setCertificationStatus("VERIFIED"); f.identity.setLocalStatus("VERIFIED");
        f.identity.setClientCorpId("default-owner-client"); f.identity.setOpenCorpId("corp-3");
        f.identity.setBindingStatus("authorized");
        when(f.gateway.getCompany(anyString(), any())).thenReturn(ownerAccount());
        onlyOldGeneratedAliasLookupMisses(f);
        var callback = companyCallback().put("clientCorpId", "tradepass-company-3")
                .put("_verifiedEvent", "corp-cancel-authorization");
        var result = f.service.syncCallback("tradepass-company-3", "corp-3", callback);
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.callbackObsolete()).isTrue();
        assertThat(f.identity.getBindingStatus()).isEqualTo("authorized");
        assertThat(f.identity.getAuthorizationEventType()).isNull();
        assertThat(f.identity.getAuthorizationRevokedAt()).isNull();
        verify(f.gateway).getCompany("default-owner-client", "corp-3");
        verifyNoInteractions(f.certifications);
    }

    private static void onlyOldGeneratedAliasLookupMisses(Fixture fixture) {
        when(fixture.identities.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FadadaCorpIdentityDO>) invocation.getArgument(0);
            String sql = query.getSqlSegment();
            return sql.contains("client_corp_id") && query.getParamNameValuePairs().containsValue("tradepass-company-3")
                    ? null : fixture.identity;
        });
    }

    private static void trustOwner(Fixture f) {
        f.properties.setOwnerEnterpriseAppId("test-app");
        f.properties.setOwnerEnterpriseOpenCorpId("corp-3");
        f.properties.setOwnerEnterpriseCreditCode("TEST-CREDIT");
        f.properties.setOwnerEnterpriseClientCorpId("default-owner-client");
    }

    private static FadadaCompanyGateway.CompanyAccount ownerAccount() {
        return new FadadaCompanyGateway.CompanyAccount("default-owner-client", "corp-3", "authorized", "identified", "enable", onboardingScopes());
    }

    @Test
    void anAlreadyAuthorizedButDifferentEnterpriseCannotBeUsedForFirstEnrollment() {
        var f = new Fixture(false); f.superAdmin();
        when(f.gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                "corp-3", "identified", "其他企业", "OTHER-CREDIT", "张三", "legal_rep", null,
                "2024-01-01T10:00:00", "legal_rep", "open-user-7", true));
        AuthContext.set(7L, null);
        try {
            assertThatThrownBy(() -> f.service.createAuthUrl(3L)).hasMessageContaining("名称或统一社会信用代码不一致");
            verify(f.gateway, never()).createAuthUrl(any());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void absentEnterpriseConsentStillOpensTheNormalProviderAuthorizationFlow() {
        var f = new Fixture();
        var missing = new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("210032");
        when(f.gateway.getCompany(anyString(), any())).thenThrow(missing);
        when(f.gateway.getCompanyByCreditCode("TEST-CREDIT")).thenThrow(missing);
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/new-consent");
        AuthContext.set(7L, null);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isEqualTo("https://example.test/new-consent");
            verify(f.gateway).getCompanyByCreditCode("TEST-CREDIT");
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void aLimitedAuthorizationQueryDoesNotBlindlyCreateAnotherAuthorizationPage() {
        var f = new Fixture();
        when(f.gateway.getCompany(anyString(), any())).thenThrow(
                new com.tradepass.framework.fadada.core.FadadaCompanyQueryException("100020"));
        AuthContext.set(7L, null);
        try {
            var result = f.service.createAuthUrl(3L);
            assertThat(result.url()).isNull();
            assertThat(result.status()).isEqualTo("IN_PROGRESS");
            assertThat(f.identity.getFailureReason()).contains("30秒");
            assertThat(f.service.current(3L).companyAuthorizationReady()).isFalse();
            verify(f.gateway, never()).getCompanyByCreditCode(any());
            verify(f.gateway, never()).createAuthUrl(any());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    @Test
    void currentOrCooledDownStateCannotPresentOldScopesAsAFreshAuthorizationConfirmation() {
        var f = new Fixture(false); f.superAdmin(); f.documentedOperator("deputy_auth", "purchasers-open-user");
        var current = f.service.sync(3L);
        assertThat(current.status()).isEqualTo("IN_PROGRESS");
        assertThat(current.companyAuthorizationReady()).isTrue();
        assertThat(current.organizationAuthorized()).isFalse();
        assertThat(current.withCallbackObsolete(true).companyAuthorizationReady()).isTrue();
        assertThat(f.service.current(3L).companyAuthorizationReady()).isFalse();
        AuthContext.set(7L, null);
        try {
            assertThat(f.service.syncCurrent(3L).companyAuthorizationReady()).isFalse();
            verify(f.gateway, times(1)).getCompany(anyString(), any());
        } finally { AuthContext.clear(); }
    }

    @Test
    void aRecentSignedRevocationCannotBeOverruledByTheExistingAuthorizationPreflight() {
        var f = new Fixture(false); f.superAdmin();
        f.identity.setAuthorizationEventType("corp-cancel-authorization");
        f.identity.setAuthorizationRevokedAt(java.time.LocalDateTime.now());
        when(f.gateway.createAuthUrl(any())).thenReturn("https://example.test/renew-consent");
        AuthContext.set(7L, null);
        try {
            assertThat(f.service.createAuthUrl(3L).url()).isEqualTo("https://example.test/renew-consent");
            verify(f.gateway, never()).getCompany(anyString(), any());
            verifyNoInteractions(f.certifications);
        } finally { AuthContext.clear(); }
    }

    private static List<String> onboardingScopes() {
        return java.util.stream.Stream.concat(SCOPES.stream(), java.util.stream.Stream.of("organization")).toList();
    }

    private static class Fixture {
        final FadadaCorpIdentityMapper identities = mock(FadadaCorpIdentityMapper.class);
        final FadadaCorpSealMapper seals = mock(FadadaCorpSealMapper.class);
        final CompanyMapper companies = mock(CompanyMapper.class);
        final SysUserMapper users = mock(SysUserMapper.class);
        final CompanyCertificationService certifications = mock(CompanyCertificationService.class);
        final FadadaPersonalIdentityService personal = mock(FadadaPersonalIdentityService.class);
        final FadadaCompanyGateway gateway = mock(FadadaCompanyGateway.class);
        final FadadaCorpIdentityDO identity = new FadadaCorpIdentityDO();
        final CompanyDO company = new CompanyDO();
        final FadadaProperties properties = new FadadaProperties();
        final AccessControlService accessControl = mock(AccessControlService.class);
        final CompanyOnboardingAttemptService attempts = mock(CompanyOnboardingAttemptService.class);
        final FadadaCompanyService service;
        Fixture() { this(true); }
        Fixture(boolean providerConfirmed) {
            MybatisTestSupport.initialize(CompanyDO.class, FadadaCorpIdentityDO.class, com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpSealDO.class);
            company.setId(3L); company.setName("认证企业");
            company.setCreditCode("TEST-CREDIT"); company.setCertificationStatus("PENDING_REVIEW");
            identity.setId(5L); identity.setCompanyId(3L); identity.setApplicantUserId(7L);
            identity.setClientCorpId("local-3"); identity.setLocalStatus("IN_PROGRESS");
            identity.setSubmittedAt(java.time.LocalDateTime.now().minusMinutes(1));
            when(identities.selectOne(any(Wrapper.class))).thenReturn(identity);
            when(identities.adoptProviderBinding(anyLong(), anyLong(), anyString(), anyString(), anyString())).thenReturn(1);
            when(companies.selectByIdForUpdate(3L)).thenReturn(company);
            when(personal.verifiedOpenUserId(7L)).thenReturn("open-user-7");
            when(gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                    "local-3", "corp-3", "authorized", "identified", "enable", SCOPES));
            properties.setOperatorOpenUserIdConfirmed(providerConfirmed);
            properties.setEnabled(true); properties.setAppId("test-app"); properties.setAppSecret("test-secret");
            properties.setServerUrl("https://example.test"); properties.setCallbackUrl("https://example.test/callback");
            service = new FadadaCompanyServiceImpl(identities, seals, companies, users, accessControl,
                    certifications, personal, gateway, properties, new ObjectMapper());
        }
        void superAdmin() {
            when(gateway.getCompany(anyString(), any())).thenReturn(new FadadaCompanyGateway.CompanyAccount(
                    "local-3", "corp-3", "authorized", "identified", "enable",
                    java.util.stream.Stream.concat(SCOPES.stream(), java.util.stream.Stream.of("organization")).toList()));
            when(gateway.getMember("corp-3", 88L)).thenReturn(new FadadaCompanyGateway.CompanyMember(
                    88L, "activated", List.of("super_admin"), "fdd-user-7"));
            when(personal.verifiedFddId(7L)).thenReturn("fdd-user-7");
            when(personal.verifiedPerson(7L)).thenReturn(new FadadaPersonalIdentityService.VerifiedPerson(
                    "open-user-7", "fdd-user-7", "张三"));
        }
        void documentedOperator(String type, String operatorId) {
            when(gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                    "corp-3", "identified", "认证企业", "TEST-CREDIT", "张三", "legal_rep", null,
                    "2024-01-01T10:00:00", type, operatorId, true));
        }
        void detail(String type, String operatorId, String status) {
            when(gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                    "corp-3", status, "认证企业", "TEST-CREDIT", "张三", "letter", null, null, type, operatorId));
        }
        void detailWithoutOperator(String identMethod) {
            when(gateway.getIdentity("corp-3")).thenReturn(new FadadaCompanyGateway.CompanyIdentity(
                    "corp-3", "identified", "认证企业", "TEST-CREDIT", "张三", identMethod, null, null, null, null));
        }
    }

    @Test void cachedProviderCertificationCannotAuthorizeChangedOrUnverifiedCompany() {
        MybatisTestSupport.initialize(FadadaCorpIdentityDO.class);
        var identities = mock(FadadaCorpIdentityMapper.class);
        var companies = mock(CompanyMapper.class);
        var service = new FadadaCompanyServiceImpl(identities, mock(FadadaCorpSealMapper.class), companies, mock(SysUserMapper.class),
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
