package com.tradepass.module.identity.service.certification;

import com.tradepass.module.identity.service.company.TenantBootstrapService;
import com.tradepass.module.identity.service.permission.AccessControlService;

import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.controller.app.certification.vo.CertificationReviewReqVO;
import com.tradepass.module.identity.api.certification.dto.CertificationApplicationRespDTO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.certification.CompanyCertificationApplicationDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.tradepass.module.identity.service.certification.CompanyCertificationService.CertifiedApplicantRole;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyCertificationServiceTest {
    private CompanyMapper companyMapper;
    private CompanyMemberMapper memberMapper;
    private CompanyCertificationApplicationMapper applicationMapper;
    private AccessControlService accessControlService;
    private TenantBootstrapService tenantBootstrapService;
    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(CompanyDO.class, CompanyMemberDO.class, CompanyCertificationApplicationDO.class);
        companyMapper = mock(CompanyMapper.class);
        memberMapper = mock(CompanyMemberMapper.class);
        applicationMapper = mock(CompanyCertificationApplicationMapper.class);
        accessControlService = mock(AccessControlService.class);
        tenantBootstrapService = mock(TenantBootstrapService.class);
        auditLogService = mock(AuditLogService.class);
        when(memberMapper.update(any(Wrapper.class))).thenReturn(1);
        when(applicationMapper.update(any(Wrapper.class))).thenReturn(1);
        doAnswer(invocation -> {
            CompanyCertificationApplicationDO application = invocation.getArgument(0);
            application.setId(18L);
            return 1;
        }).when(applicationMapper).insert(any(CompanyCertificationApplicationDO.class));
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void developmentSubmissionCompletesOnboardingAndInitializesTenant() {
        CompanyDO company = company();
        company.setRealNameStatus("VERIFIED");
        company.setFaceStatus("VERIFIED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        CompanyCertificationService service = service(true, "");

        CertificationApplicationRespDTO payload = service.submit(3L);

        assertThat(payload.status()).isEqualTo("APPROVED");
        assertThat(payload.providerRequestId()).startsWith("MOCK-CA-");
        verify(accessControlService).requireLegalOrClaim(3L);
        verify(tenantBootstrapService).initialize(3L, 7L);
        verify(auditLogService).logAs(3L, 7L, "COMPANY_CERTIFICATION", 18L,
                "APPROVE", "体验测试模拟认证自动审核");
    }

    @Test
    void productionCallbackIsSecretProtectedAndIdempotent() {
        CompanyDO company = company();
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        CompanyCertificationApplicationDO application = new CompanyCertificationApplicationDO();
        application.setId(18L);
        application.setCompanyId(3L);
        application.setApplicantUserId(7L);
        application.setProviderRequestId("provider-18");
        application.setStatus("SUBMITTED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application);
        CompanyCertificationService service = service(false, "callback-secret");
        CertificationReviewReqVO approved = new CertificationReviewReqVO("provider-18", "APPROVED", "核验通过");

        assertThatThrownBy(() -> service.review("bad", approved))
                .isInstanceOf(BusinessException.class).hasMessage("认证回调凭证无效");
        assertThatThrownBy(() -> service.review("callback-secret", approved))
                .hasMessageContaining("核验经办人身份");
        service.completeProviderCertification(3L, 7L, "provider-18", "核验通过", CertifiedApplicantRole.ADMIN);
        assertThat(service.review("callback-secret", approved).status()).isEqualTo("APPROVED");
        verify(tenantBootstrapService).initialize(3L, 7L);
    }

    @Test
    void authorizedAgentBecomesAdministratorWithoutLegalFlagOrInheritedAllPermissions() {
        assertAssignedRole(CertifiedApplicantRole.ADMIN, false, true);
    }

    @Test
    void verifiedLegalOperatorBecomesLegalRepresentative() {
        assertAssignedRole(CertifiedApplicantRole.LEGAL, true, false);
    }

    private void assertAssignedRole(CertifiedApplicantRole role, boolean legal, boolean administrator) {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.update(any(Wrapper.class))).thenAnswer(invocation -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyMemberDO>) invocation.getArgument(0);
            assertThat(update.getSqlSegment()).contains("role_code", "status");
            assertThat(update.getSqlSet()).contains("role_code=", "role_codes=", "is_legal_person=", "is_administrator=", "custom_permissions=");
            var values = update.getParamNameValuePairs();
            for (String assignment : update.getSqlSet().split(",")) {
                String key = assignment.substring(assignment.indexOf("MPGENVAL"), assignment.indexOf('}'));
                Object value = values.get(key);
                if (assignment.startsWith("role_code=")) assertThat(value).isEqualTo(role.name());
                if (assignment.startsWith("role_codes=")) assertThat(value).isEqualTo("[\"" + role.name() + "\"]");
                if (assignment.startsWith("is_legal_person=")) assertThat(value).isEqualTo(legal);
                if (assignment.startsWith("is_administrator=")) assertThat(value).isEqualTo(administrator);
                if (assignment.startsWith("custom_permissions=")) assertThat(value).isNull();
            }
            return 1;
        });
        service(false, "").completeProviderCertification(3L, 7L, "corp-3", "已核验", role);
        verify(tenantBootstrapService).initialize(3L, 7L);
    }

    @Test
    void missingOperatorRoleCannotActivateAnyMembership() {
        assertThatThrownBy(() -> service(false, "").completeProviderCertification(3L, 7L, "corp-3", "", null))
                .hasMessageContaining("身份尚未确认");
        org.mockito.Mockito.verifyNoInteractions(companyMapper, memberMapper, tenantBootstrapService);
    }

    @Test
    void acceptsLegacyCompanyWhoseApplicantIsAlreadyTheActiveLegalMember() {
        CompanyDO company = company();
        company.setRealNameStatus("VERIFIED");
        company.setFaceStatus("VERIFIED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        when(memberMapper.update(any(Wrapper.class))).thenReturn(0);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThat(service(true, "").submit(3L).status()).isEqualTo("APPROVED");
        verify(tenantBootstrapService).initialize(3L, 7L);
    }

    @Test
    void currentProviderSuccessRestoresRejectedCompanyWithoutRegrantingRevokedMembership() {
        CompanyDO company = company();
        company.setCertificationStatus("REJECTED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        CompanyCertificationApplicationDO approved = application("APPROVED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approved);

        service(false, "").completeProviderCertification(3L, 7L, "corp-3", "重新核验已通过", CertifiedApplicantRole.ADMIN);

        verify(companyMapper).update(org.mockito.ArgumentMatchers.argThat((Wrapper<CompanyDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyDO>) wrapper;
            return update.getSqlSet().contains("certification_status")
                    && update.getParamNameValuePairs().containsValue("VERIFIED");
        }));
        org.mockito.Mockito.verifyNoInteractions(memberMapper, tenantBootstrapService);
        assertThat(approved.getStatus()).isEqualTo("APPROVED");
    }

    @Test
    void rejectedApplicationCanBeApprovedByNewProviderEvidence() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        CompanyCertificationApplicationDO rejected = application("REJECTED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(rejected);
        service(false, "").completeProviderCertification(3L, 7L, "corp-3", "重新核验已通过", CertifiedApplicantRole.ADMIN);
        assertThat(rejected.getStatus()).isEqualTo("APPROVED");
        verify(tenantBootstrapService).initialize(3L, 7L);
        verify(applicationMapper).update(org.mockito.ArgumentMatchers.argThat((Wrapper<CompanyCertificationApplicationDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyCertificationApplicationDO>) wrapper;
            update.getSqlSegment();
            return update.getParamNameValuePairs().containsValue("REJECTED")
                    && update.getParamNameValuePairs().containsValue("APPROVED");
        }));
    }

    @Test
    void alreadyCertifiedCompanyStillValidatesTheApplicationApplicant() {
        CompanyDO company = company(); company.setCertificationStatus("VERIFIED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        CompanyCertificationApplicationDO approved = application("APPROVED"); approved.setApplicantUserId(99L);
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approved);
        assertThatThrownBy(() -> service(false, "").completeProviderCertification(3L, 7L,
                "corp-3", "", CertifiedApplicantRole.LEGAL)).hasMessageContaining("经办人不一致");
        org.mockito.Mockito.verifyNoInteractions(memberMapper);
    }

    @Test
    void onlyApprovedMembersCanClaimVacantLegalPosition() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> service(false, "").requireLegalClaim(3L, 7L)).hasMessageContaining("成员邀请");
    }

    @Test
    void legalClaimCannotReplaceAnExistingOtherLegalRepresentative() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 1L);
        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "legal-3"))
                .hasMessageContaining("本企业已有法人");
        org.mockito.Mockito.verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
    }

    @Test
    void legalClaimPromotesOnlyTheProvenCurrentActiveMember() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);
        CompanyCertificationApplicationDO approved = application("APPROVED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approved);
        service(false, "").completeLegalClaim(3L, 7L, "corp-3");
        verify(memberMapper).update(org.mockito.ArgumentMatchers.argThat((Wrapper<CompanyMemberDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyMemberDO>) wrapper;
            update.getSqlSegment();
            return update.getSqlSet().contains("is_legal_person")
                    && update.getParamNameValuePairs().containsValue("LEGAL")
                    && update.getParamNameValuePairs().containsValue("ACTIVE")
                    && update.getParamNameValuePairs().containsValue(7L);
        }));
    }

    private CompanyCertificationApplicationDO application(String status) {
        CompanyCertificationApplicationDO application = new CompanyCertificationApplicationDO();
        application.setId(18L); application.setCompanyId(3L); application.setApplicantUserId(7L);
        application.setProviderRequestId("corp-3"); application.setStatus(status);
        return application;
    }

    private CompanyCertificationService service(boolean autoApprove, String token) {
        return new CompanyCertificationServiceImpl(companyMapper, memberMapper, applicationMapper,
                accessControlService, tenantBootstrapService, auditLogService, autoApprove, token);
    }

    private CompanyDO company() {
        CompanyDO company = new CompanyDO();
        company.setId(3L);
        company.setName("测试企业");
        company.setCertificationStatus("PENDING");
        company.setRealNameStatus("NOT_STARTED");
        company.setFaceStatus("NOT_STARTED");
        return company;
    }
}
