package com.tradepass.module.identity.service.certification;

import com.tradepass.module.identity.service.company.TenantBootstrapService;
import com.tradepass.module.identity.service.company.CompanyOnboardingAttemptService;
import com.tradepass.module.identity.service.permission.AccessControlService;

import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.controller.app.certification.vo.CertificationReviewReqVO;
import com.tradepass.module.identity.api.certification.dto.CertificationApplicationRespDTO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.certification.CompanyCertificationApplicationDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyOnboardingAttemptDO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.tradepass.module.identity.service.certification.CompanyCertificationService.CertifiedApplicantRole;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.ArgumentMatchers.argThat;

class CompanyCertificationServiceTest {
    private CompanyMapper companyMapper;
    private CompanyMemberMapper memberMapper;
    private CompanyCertificationApplicationMapper applicationMapper;
    private AccessControlService accessControlService;
    private TenantBootstrapService tenantBootstrapService;
    private AuditLogService auditLogService;
    private CompanyOnboardingAttemptService onboardingAttempts;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(CompanyDO.class, CompanyMemberDO.class, CompanyCertificationApplicationDO.class);
        companyMapper = mock(CompanyMapper.class);
        memberMapper = mock(CompanyMemberMapper.class);
        applicationMapper = mock(CompanyCertificationApplicationMapper.class);
        accessControlService = mock(AccessControlService.class);
        tenantBootstrapService = mock(TenantBootstrapService.class);
        auditLogService = mock(AuditLogService.class);
        onboardingAttempts = mock(CompanyOnboardingAttemptService.class);
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
    void managedSubmissionUsesItsCurrentRequestInsteadOfAnotherPendingLegacyApplication() {
        CompanyDO company = company();
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        var attempt = activeAttempt();
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.requireCurrent(3L, 7L)).thenReturn(attempt);
        CompanyCertificationApplicationDO unrelated = application("SUBMITTED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (LambdaQueryWrapper<CompanyCertificationApplicationDO>) invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue(attempt.getProviderRequestId()) ? null : unrelated;
        });

        var result = managedService(false, "").submit(3L);

        assertThat(result.providerRequestId()).isEqualTo(attempt.getProviderRequestId());
        assertThat(result.companyName()).isEqualTo(attempt.getCompanyName());
        verify(applicationMapper).insert(argThat((CompanyCertificationApplicationDO inserted) ->
                attempt.getProviderRequestId().equals(inserted.getProviderRequestId())
                        && inserted.getCompanyId() == 3L && inserted.getApplicantUserId() == 7L));
        verifyNoInteractions(memberMapper, tenantBootstrapService);
    }

    @Test
    void draftApplicantCannotSubmitCertificationWhileAnotherApplicantOwnsTheActiveAttempt() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        doThrow(new BusinessException("本次企业认证申请已失效，请重新申请"))
                .when(onboardingAttempts).requireCurrent(3L, 7L);

        assertThatThrownBy(() -> managedService(false, "").submit(3L)).hasMessageContaining("已失效");

        verifyNoInteractions(applicationMapper, memberMapper, tenantBootstrapService, auditLogService);
        verify(companyMapper, never()).update(any(Wrapper.class));
    }

    @Test
    void managedSubmissionCannotReopenACompletedCertificationRequest() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        var attempt = activeAttempt();
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.requireCurrent(3L, 7L)).thenReturn(attempt);
        var rejected = application("REJECTED");
        rejected.setProviderRequestId(attempt.getProviderRequestId());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(rejected);

        assertThatThrownBy(() -> managedService(false, "").submit(3L)).hasMessageContaining("已结束");

        verify(applicationMapper, never()).insert(any(CompanyCertificationApplicationDO.class));
        verifyNoInteractions(memberMapper, tenantBootstrapService, auditLogService);
    }

    @Test
    void reviewReloadsCancellationUnderTheCompanyLockInsteadOfUsingItsEarlierSnapshot() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        var stale = application("SUBMITTED");
        var cancelled = application("CANCELLED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(stale, cancelled);

        assertThatThrownBy(() -> service(false, "secret").review("secret",
                new CertificationReviewReqVO("corp-3", "REJECTED", "旧申请驳回")))
                .hasMessageContaining("已完成");

        var order = inOrder(applicationMapper, companyMapper);
        order.verify(applicationMapper).selectOne(any(Wrapper.class));
        order.verify(companyMapper).selectByIdForUpdate(3L);
        order.verify(applicationMapper).selectOne(argThat((Wrapper<CompanyCertificationApplicationDO> query) ->
                query.getSqlSegment().contains("FOR UPDATE")));
        verify(companyMapper, never()).update(any(Wrapper.class));
        verify(applicationMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(memberMapper, tenantBootstrapService, auditLogService);
    }

    @Test
    void anOldRequestFromTheSameApplicantCannotRejectTheirReplacementApplication() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application("SUBMITTED"));
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.requireCurrent(3L, 7L)).thenReturn(activeAttempt());

        assertThatThrownBy(() -> managedService(false, "secret").review("secret",
                new CertificationReviewReqVO("corp-3", "REJECTED", "旧轮次驳回")))
                .hasMessageContaining("替代");

        assertNoCertificationOutcomeWritten();
    }

    @Test
    void anExpiredOrDifferentApplicantsRequestCannotRejectTheCurrentApplication() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application("SUBMITTED"));
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        doThrow(new BusinessException("本次企业认证申请已失效，请重新申请"))
                .when(onboardingAttempts).requireCurrent(3L, 7L);

        assertThatThrownBy(() -> managedService(false, "secret").review("secret",
                new CertificationReviewReqVO("corp-3", "REJECTED", "旧申请驳回")))
                .hasMessageContaining("已失效");

        assertNoCertificationOutcomeWritten();
    }

    @Test
    void anUnfinishedLegacyRequestCannotRejectAnAlreadyEstablishedEnterprise() {
        var company = company();
        company.setCertificationStatus("VERIFIED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application("SUBMITTED"));

        assertThatThrownBy(() -> service(false, "secret").review("secret",
                new CertificationReviewReqVO("corp-3", "REJECTED", "迟到的旧拒绝")))
                .hasMessageContaining("已正式开通");

        assertNoCertificationOutcomeWritten();
    }

    @Test
    void theCurrentManagedRequestCanRecordItsOwnRejectionWithoutGrantingAnyRole() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        var attempt = activeAttempt();
        var pending = application("SUBMITTED");
        pending.setProviderRequestId(attempt.getProviderRequestId());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.requireCurrent(3L, 7L)).thenReturn(attempt);

        var result = managedService(false, "secret").review("secret",
                new CertificationReviewReqVO(attempt.getProviderRequestId(), "REJECTED", "本轮资料不完整"));

        assertThat(result.status()).isEqualTo("REJECTED");
        verify(companyMapper).update(argThat((Wrapper<CompanyDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyDO>) wrapper;
            return update.getSqlSet().contains("certification_status")
                    && update.getParamNameValuePairs().containsValue("REJECTED");
        }));
        verifyNoInteractions(memberMapper, tenantBootstrapService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "REJECTED"})
    void anOldProviderResultCannotPromoteTheApplicantAfterTheirRequestWasReplaced(String status) {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application(status));
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.requireCurrent(3L, 7L)).thenReturn(activeAttempt());

        assertThatThrownBy(() -> managedService(false, "").completeProviderCertification(
                3L, 7L, "corp-3", "旧轮次的迟到结果", CertifiedApplicantRole.ADMIN))
                .hasMessageContaining("替代");

        assertNoCertificationOutcomeWritten();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "REJECTED"})
    void anUnapprovedOldProviderRequestCannotGrantMembershipToAnEstablishedEnterprise(String status) {
        var company = company();
        company.setCertificationStatus("VERIFIED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application(status));

        assertThatThrownBy(() -> managedService(false, "").completeProviderCertification(
                3L, 7L, "corp-3", "过期的内部结果", CertifiedApplicantRole.ADMIN))
                .hasMessageContaining("不能重新授予");

        assertNoCertificationOutcomeWritten();
    }

    @Test
    void currentManagedProviderProofCanApproveOnlyItsCurrentCertificationRequest() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        var attempt = activeAttempt();
        var pending = application("SUBMITTED");
        pending.setProviderRequestId(attempt.getProviderRequestId());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.requireCurrent(3L, 7L)).thenReturn(attempt);

        managedService(false, "").completeProviderCertification(
                3L, 7L, attempt.getProviderRequestId(), "本轮独立身份已核验", CertifiedApplicantRole.ADMIN);

        assertThat(pending.getStatus()).isEqualTo("APPROVED");
        verify(tenantBootstrapService).initialize(3L, 7L);
        // The Fadada caller completes the attempt after this transactionally joined approval.
        verify(onboardingAttempts, never()).complete(3L, 7L, attempt.getProviderRequestId());
    }

    @Test
    void historicalApprovedRequestStillRestoresCertificationWithoutRegrantingMembership() {
        var company = company();
        company.setCertificationStatus("REJECTED");
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application("APPROVED"));
        when(onboardingAttempts.hasAttempts(3L)).thenReturn(true);
        when(onboardingAttempts.isEstablished(company)).thenReturn(true);

        managedService(false, "").completeProviderCertification(
                3L, 7L, "corp-3", "当前企业认证事实已恢复", CertifiedApplicantRole.ADMIN);

        verify(companyMapper).update(any(Wrapper.class));
        verify(onboardingAttempts, never()).requireCurrent(3L, 7L);
        verifyNoInteractions(memberMapper, tenantBootstrapService);
    }

    private void assertNoCertificationOutcomeWritten() {
        verify(companyMapper, never()).update(any(Wrapper.class));
        verify(applicationMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(memberMapper, tenantBootstrapService, auditLogService);
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
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 1L);

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
    void verifiedEnterpriseAllowsIndependentEligibilityWithoutInvitationOrMembershipWrites() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        service(false, "").requireLegalClaim(3L, 7L);

        var order = inOrder(companyMapper, memberMapper);
        order.verify(companyMapper).selectByIdForUpdate(3L);
        order.verify(memberMapper).selectCount(argThat((Wrapper<CompanyMemberDO> query) -> {
            assertThat(query.getSqlSegment()).contains("user_id <>", "FOR UPDATE", "JSON_CONTAINS");
            var lambda = (LambdaQueryWrapper<CompanyMemberDO>) query;
            assertThat(lambda.getParamNameValuePairs().values()).contains(3L, 7L, "ACTIVE", "LEGAL", true);
            return true;
        }));
        verify(memberMapper, never()).insert(any(CompanyMemberDO.class));
        verify(memberMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(applicationMapper, tenantBootstrapService, accessControlService, auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PENDING_REVIEW", "CANCELLED", "REJECTED"})
    void independentClaimRequiresFormalVerifiedStatusEvenIfOldMembersExist(String status) {
        var company = company();
        company.setCertificationStatus(status);
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company);
        when(onboardingAttempts.isEstablished(company)).thenReturn(true);

        assertThatThrownBy(() -> managedService(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim"))
                .hasMessageContaining("请先完成企业认证");

        verifyNoInteractions(memberMapper, applicationMapper, tenantBootstrapService, auditLogService);
        verify(companyMapper, never()).update(any(Wrapper.class));
    }

    @Test
    void independentlyProvenNonmemberCanCreateOnlyTheirOwnActiveLegalMembership() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        when(memberMapper.insert(any(CompanyMemberDO.class))).thenReturn(1);

        service(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim");

        var order = inOrder(companyMapper, memberMapper, applicationMapper);
        order.verify(companyMapper).selectByIdForUpdate(3L);
        order.verify(memberMapper).selectCount(argThat((Wrapper<CompanyMemberDO> query) ->
                query.getSqlSegment().contains("FOR UPDATE")));
        order.verify(memberMapper).selectOne(argThat((Wrapper<CompanyMemberDO> query) -> {
            assertThat(query.getSqlSegment()).contains("LIMIT 1 FOR UPDATE");
            var lambda = (LambdaQueryWrapper<CompanyMemberDO>) query;
            assertThat(lambda.getParamNameValuePairs().values()).contains(3L, 7L);
            return true;
        }));
        order.verify(applicationMapper).selectOne(argThat((Wrapper<CompanyCertificationApplicationDO> query) ->
                query.getSqlSegment().contains("FOR UPDATE")));
        verify(memberMapper).insert(argThat((CompanyMemberDO member) ->
                member.getCompanyId() == 3L && member.getUserId() == 7L
                        && "LEGAL".equals(member.getRoleCode()) && "[\"LEGAL\"]".equals(member.getRoleCodes())
                        && "ACTIVE".equals(member.getStatus()) && Boolean.TRUE.equals(member.getIsLegalPerson())
                        && Boolean.FALSE.equals(member.getIsAdministrator())));
        verify(applicationMapper).insert(argThat((CompanyCertificationApplicationDO application) ->
                application.getCompanyId() == 3L && application.getApplicantUserId() == 7L
                        && "fresh-claim".equals(application.getProviderRequestId())
                        && "APPROVED".equals(application.getStatus())));
        verify(memberMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(tenantBootstrapService, accessControlService);
        verify(auditLogService).logAs(3L, 7L, "COMPANY_CERTIFICATION", 18L, "LEGAL_CLAIM",
                "认证服务已独立核实法人本人；创建或激活本人成员，保留既有成员和权限");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "INACTIVE", "REMOVED", "REJECTED"})
    void freshIndependentClaimActivatesOnlyThisMemberAndPreservesExistingBusinessPermissions(String status) {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        CompanyMemberDO member = activeAdministrator();
        member.setStatus(status);
        member.setRoleCode("LEGAL_CANDIDATE");
        member.setRoleCodes("[\"LEGAL_CANDIDATE\",\"GUEST\",\"FINANCE\",\"CUSTOM_AUDIT\"]");
        member.setCustomPermissions("[\"order_view\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);

        service(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim");

        verify(memberMapper).update(argThat((Wrapper<CompanyMemberDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyMemberDO>) wrapper;
            update.getSqlSegment();
            assertThat(update.getSqlSet()).contains("role_code", "role_codes", "status", "is_legal_person")
                    .doesNotContain("custom_permissions", "is_administrator");
            assertThat(update.getParamNameValuePairs().values()).contains(12L, 3L, 7L, status,
                    "LEGAL_CANDIDATE", "ACTIVE", "LEGAL", member.getRoleCodes(),
                    "[\"FINANCE\",\"CUSTOM_AUDIT\",\"LEGAL\"]");
            return true;
        }));
        assertThat(member.getCustomPermissions()).isEqualTo("[\"order_view\"]");
        assertThat(member.getIsAdministrator()).isTrue();
        verify(memberMapper, never()).insert(any(CompanyMemberDO.class));
        verifyNoInteractions(tenantBootstrapService, accessControlService);
    }

    @Test
    void alreadyActiveLegalClaimIsIdempotentWithoutAnotherApplicationOrAuditRecord() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        CompanyMemberDO member = activeAdministrator();
        member.setRoleCode("LEGAL"); member.setRoleCodes("[\"ADMIN\",\"LEGAL\"]");
        member.setIsLegalPerson(true); member.setCustomPermissions("[\"order_view\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);

        service(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim");

        verify(companyMapper).update(any(Wrapper.class));
        verify(memberMapper, never()).insert(any(CompanyMemberDO.class));
        verify(memberMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(applicationMapper, auditLogService, tenantBootstrapService, accessControlService);
        assertThat(member.getRoleCodes()).isEqualTo("[\"ADMIN\",\"LEGAL\"]");
        assertThat(member.getCustomPermissions()).isEqualTo("[\"order_view\"]");
        assertThat(member.getIsAdministrator()).isTrue();
    }

    @Test
    void independentClaimCannotReplaceAnotherActiveLegalMember() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim"))
                .hasMessageContaining("本企业已有法人");

        verify(memberMapper, never()).selectOne(any(Wrapper.class));
        verify(memberMapper, never()).insert(any(CompanyMemberDO.class));
        verify(memberMapper, never()).update(any(Wrapper.class));
        verify(companyMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(applicationMapper, auditLogService, tenantBootstrapService);
    }

    @Test
    void anApprovedHistoricalIndependentClaimCannotRestoreRemovedMembership() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        var member = activeAdministrator(); member.setStatus("REMOVED");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(application("APPROVED"));

        assertThatThrownBy(() -> service(false, "").completeIndependentLegalClaim(3L, 7L, "corp-3"))
                .hasMessageContaining("请重新办理独立核验");

        verify(memberMapper, never()).insert(any(CompanyMemberDO.class));
        verify(memberMapper, never()).update(any(Wrapper.class));
        verify(applicationMapper, never()).update(any(Wrapper.class));
        verify(companyMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(auditLogService, tenantBootstrapService);
    }

    @Test
    void independentClaimDoesNotCompleteIfConcurrentInsertConflicts() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        when(memberMapper.insert(any(CompanyMemberDO.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("member collision"));

        assertThatThrownBy(() -> service(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim"))
                .hasMessageContaining("成员状态已变化");

        verify(applicationMapper, never()).update(any(Wrapper.class));
        verify(companyMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(auditLogService, tenantBootstrapService);
    }

    @Test
    void independentClaimDoesNotCompleteIfTargetMembershipCompareAndSetLoses() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(verifiedCompany());
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeAdministrator());
        when(memberMapper.update(any(Wrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service(false, "").completeIndependentLegalClaim(3L, 7L, "fresh-claim"))
                .hasMessageContaining("成员状态已变化");

        verify(applicationMapper, never()).update(any(Wrapper.class));
        verify(companyMapper, never()).update(any(Wrapper.class));
        verifyNoInteractions(auditLogService, tenantBootstrapService);
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
        CompanyCertificationApplicationDO pending = application("SUBMITTED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeAdministrator());
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

    @Test
    void newLegalEvidenceRestoresOriginalAdministratorIdempotentlyWithoutErasingLaterRoleSettings() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        CompanyCertificationApplicationDO original = approvedAdministratorApplication();
        original.setReviewedAt(LocalDateTime.of(2026, 10, 14, 16, 58, 9));
        AtomicReference<CompanyCertificationApplicationDO> supplementary = new AtomicReference<>();
        when(applicationMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (LambdaQueryWrapper<CompanyCertificationApplicationDO>) invocation.getArgument(0);
            query.getSqlSegment();
            return query.getParamNameValuePairs().containsValue("corp-3") ? original : supplementary.get();
        });
        doAnswer(invocation -> {
            CompanyCertificationApplicationDO inserted = invocation.getArgument(0);
            inserted.setId(19L);
            supplementary.set(inserted);
            return 1;
        }).when(applicationMapper).insert(any(CompanyCertificationApplicationDO.class));
        when(memberMapper.selectCount(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<?> query = invocation.getArgument(0);
            return query.getSqlSegment().contains("user_id <>") ? 0L : 1L;
        });
        CompanyMemberDO member = activeAdministrator();
        member.setRoleCodes("[\"ADMIN\",\"SALES\",\"FINANCE\"]");
        member.setCustomPermissions("[\"order_create\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            Wrapper<?> query = invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("FOR UPDATE");
            return member;
        });
        when(memberMapper.update(any(Wrapper.class))).thenAnswer(invocation -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyMemberDO>) invocation.getArgument(0);
            update.getSqlSegment();
            assertThat(update.getSqlSet()).doesNotContain("custom_permissions", "is_administrator");
            assertThat(update.getParamNameValuePairs().values())
                    .contains(12L, 3L, 7L, "ACTIVE", "ADMIN", "[\"ADMIN\",\"SALES\",\"FINANCE\"]",
                            "[\"ADMIN\",\"SALES\",\"FINANCE\",\"LEGAL\"]");
            member.setRoleCode("LEGAL");
            member.setRoleCodes("[\"ADMIN\",\"SALES\",\"FINANCE\",\"LEGAL\"]");
            member.setIsLegalPerson(true);
            return 1;
        });
        CompanyCertificationService service = service(false, "");

        service.completeProviderCertification(3L, 7L, "corp-3", "本次已明确核实法人本人", CertifiedApplicantRole.LEGAL);
        service.completeProviderCertification(3L, 7L, "corp-3", "重复同步", CertifiedApplicantRole.LEGAL);
        service.completeLegalClaim(3L, 7L, "FDD-LEGAL-3-7");

        verify(memberMapper).update(any(Wrapper.class));
        verify(applicationMapper).insert(any(CompanyCertificationApplicationDO.class));
        verify(applicationMapper).update(org.mockito.ArgumentMatchers.argThat((Wrapper<CompanyCertificationApplicationDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyCertificationApplicationDO>) wrapper;
            update.getSqlSegment();
            assertThat(update.getParamNameValuePairs().values()).contains(19L).doesNotContain(18L);
            return true;
        }));
        assertThat(original.getReviewReason()).isEqualTo("企业授权已确认，开通管理员身份；法人身份需单独核验");
        assertThat(original.getReviewedAt()).isEqualTo(LocalDateTime.of(2026, 10, 14, 16, 58, 9));
        assertThat(supplementary.get().getProviderRequestId()).isEqualTo("FDD-LEGAL-3-7");
        assertThat(supplementary.get().getStatus()).isEqualTo("APPROVED");
        assertThat(member.getCustomPermissions()).isEqualTo("[\"order_create\"]");
        assertThat(member.getIsAdministrator()).isTrue();
        verify(auditLogService).logAs(3L, 7L, "COMPANY_CERTIFICATION", 19L,
                "LEGAL_CLAIM", "认证服务已核实法人身份及本人账号；保留既有角色和自定义权限");
        org.mockito.Mockito.verifyNoInteractions(tenantBootstrapService);

        member.setRoleCode("ADMIN"); member.setRoleCodes("[\"ADMIN\",\"SALES\",\"FINANCE\"]");
        member.setIsLegalPerson(false);
        assertThatThrownBy(() -> service.completeLegalClaim(3L, 7L, "FDD-LEGAL-3-7"))
                .hasMessageContaining("成员身份已变更");
        assertThatThrownBy(() -> service.completeProviderCertification(3L, 7L, "corp-3", "再次自动同步", CertifiedApplicantRole.LEGAL))
                .hasMessageContaining("成员身份已变更");
        verify(memberMapper).update(any(Wrapper.class));
    }

    @Test
    void arbitraryApprovedApplicationDoesNotRegrantLegalRole() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        CompanyCertificationApplicationDO approved = application("APPROVED");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approved);

        service(false, "").completeProviderCertification(3L, 7L, "corp-3", "新结果", CertifiedApplicantRole.LEGAL);
        approved.setReviewReason("历史审核原因不明");
        service(false, "").completeProviderCertification(3L, 7L, "corp-3", "再次同步", CertifiedApplicantRole.LEGAL);

        org.mockito.Mockito.verifyNoInteractions(memberMapper, tenantBootstrapService);
        verify(applicationMapper, org.mockito.Mockito.never()).insert(any(CompanyCertificationApplicationDO.class));
    }

    @Test
    void originalAdministratorEvidenceAloneDoesNotGrantLegalRole() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approvedAdministratorApplication());

        service(false, "").completeProviderCertification(3L, 7L, "corp-3", "管理员同步", CertifiedApplicantRole.ADMIN);

        org.mockito.Mockito.verifyNoInteractions(memberMapper, tenantBootstrapService);
    }

    @Test
    void originalAdministratorApprovalCannotRestoreRemovedMemberOrReplaceOtherLegalMember() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approvedAdministratorApplication());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service(false, "").completeProviderCertification(3L, 7L,
                "corp-3", "法人本人证据", CertifiedApplicantRole.LEGAL)).hasMessageContaining("成员邀请");

        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 1L);
        assertThatThrownBy(() -> service(false, "").completeProviderCertification(3L, 7L,
                "corp-3", "法人本人证据", CertifiedApplicantRole.LEGAL)).hasMessageContaining("本企业已有法人");
        verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        verify(memberMapper, org.mockito.Mockito.never()).insert(any(CompanyMemberDO.class));
        verify(applicationMapper, org.mockito.Mockito.never()).insert(any(CompanyCertificationApplicationDO.class));
    }

    @Test
    void legalClaimPreservesRolesAndCustomPermissionsInSeparateSupplementaryApplication() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);
        CompanyMemberDO member = activeAdministrator();
        member.setRoleCode("FINANCE");
        member.setRoleCodes("[\"FINANCE\",\"CUSTOM_AUDIT\"]");
        member.setCustomPermissions("[\"order_view\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);

        service(false, "").completeLegalClaim(3L, 7L, "FDD-LEGAL-corp-3-7");

        verify(memberMapper).update(org.mockito.ArgumentMatchers.argThat((Wrapper<CompanyMemberDO> wrapper) -> {
            var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyMemberDO>) wrapper;
            update.getSqlSegment();
            assertThat(update.getSqlSet()).doesNotContain("custom_permissions", "is_administrator");
            assertThat(update.getParamNameValuePairs().values()).contains("[\"FINANCE\",\"CUSTOM_AUDIT\",\"LEGAL\"]");
            return true;
        }));
        verify(applicationMapper).insert(org.mockito.ArgumentMatchers.argThat((CompanyCertificationApplicationDO app) ->
                "FDD-LEGAL-corp-3-7".equals(app.getProviderRequestId()) && "APPROVED".equals(app.getStatus())));
        org.mockito.Mockito.verifyNoInteractions(tenantBootstrapService);
    }

    @Test
    void promotionFailsWhenLockedMembershipDisappearsOrCompareAndSetLoses() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L, 1L, 0L);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "legal-3"))
                .hasMessageContaining("成员状态已变化");

        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeAdministrator());
        when(memberMapper.update(any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "legal-3"))
                .hasMessageContaining("成员状态已变化");
        verify(applicationMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }

    @Test
    void approvedSupplementaryRequestCannotRegrantLaterRevokedLegalMembership() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);
        CompanyCertificationApplicationDO approved = application("APPROVED");
        approved.setProviderRequestId("FDD-LEGAL-corp-3-7");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(approved);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeAdministrator());

        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "FDD-LEGAL-corp-3-7"))
                .hasMessageContaining("该次法人核验已完成，但成员身份已变更");

        verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        verify(applicationMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        org.mockito.Mockito.verifyNoInteractions(auditLogService);
    }

    @Test
    void malformedExistingRoleConfigurationIsNeverReplacedDuringLegalClaim() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);
        CompanyMemberDO member = activeAdministrator();
        member.setRoleCodes("not-json");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);

        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "legal-3"))
                .hasMessageContaining("成员角色配置格式错误");

        verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        verify(applicationMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
    }

    @Test
    void providerRequestCannotOverflowTheApplicationColumn() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);

        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "a".repeat(65)))
                .hasMessageContaining("申请标识无效");

        org.mockito.Mockito.verifyNoInteractions(applicationMapper, auditLogService);
        verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
    }

    @Test
    void pendingLegalApprovalCannotCreateSecondLegalRepresentative() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> service(false, "").completeProviderCertification(3L, 7L,
                "corp-3", "法人本人证据", CertifiedApplicantRole.LEGAL)).hasMessageContaining("本企业已有法人");

        verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        org.mockito.Mockito.verifyNoInteractions(tenantBootstrapService);
    }

    @Test
    void supplementaryRequestMustStillBelongToTheSameCompanyApplicantAndRequest() {
        when(companyMapper.selectByIdForUpdate(3L)).thenReturn(company());
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L);
        CompanyCertificationApplicationDO other = application("APPROVED");
        other.setProviderRequestId("different-request");
        when(applicationMapper.selectOne(any(Wrapper.class))).thenReturn(other);

        assertThatThrownBy(() -> service(false, "").completeLegalClaim(3L, 7L, "legal-3"))
                .hasMessageContaining("经办人不一致");
        verify(memberMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
    }

    private CompanyCertificationApplicationDO approvedAdministratorApplication() {
        CompanyCertificationApplicationDO application = application("APPROVED");
        application.setReviewReason("企业授权已确认，开通管理员身份；法人身份需单独核验");
        return application;
    }

    private CompanyMemberDO activeAdministrator() {
        CompanyMemberDO member = new CompanyMemberDO();
        member.setId(12L);
        member.setCompanyId(3L);
        member.setUserId(7L);
        member.setRoleCode("ADMIN");
        member.setRoleCodes("[\"ADMIN\"]");
        member.setIsAdministrator(true);
        member.setIsLegalPerson(false);
        member.setStatus("ACTIVE");
        return member;
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

    private CompanyCertificationServiceImpl managedService(boolean autoApprove, String token) {
        var service = (CompanyCertificationServiceImpl) service(autoApprove, token);
        service.setOnboardingAttemptService(onboardingAttempts);
        return service;
    }

    private CompanyOnboardingAttemptDO activeAttempt() {
        var attempt = new CompanyOnboardingAttemptDO();
        attempt.setId(501L);
        attempt.setCompanyId(3L);
        attempt.setApplicantUserId(7L);
        attempt.setProviderRequestId("FDD-ATT-current-7");
        attempt.setCompanyName("本人的重新申请企业");
        attempt.setStatus("ACTIVE");
        attempt.setStartedAt(LocalDateTime.now().minusMinutes(1));
        attempt.setExpiresAt(LocalDateTime.now().plusMinutes(29));
        return attempt;
    }

    private CompanyDO verifiedCompany() {
        CompanyDO company = company();
        company.setCertificationStatus("VERIFIED");
        return company;
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
