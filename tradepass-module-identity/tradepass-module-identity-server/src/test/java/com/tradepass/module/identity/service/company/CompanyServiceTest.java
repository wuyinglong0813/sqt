package com.tradepass.module.identity.service.company;

import com.tradepass.module.identity.service.permission.RolePermissionServiceImpl;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.module.identity.service.notice.MemberRemovalNoticeService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionService;

import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.AuthorizationRecord;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyProfile;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanySearchSummary;
import com.tradepass.module.identity.controller.app.company.vo.ApproveReqVO;
import com.tradepass.module.identity.controller.app.company.vo.CompanySubmitReqVO;
import com.tradepass.module.identity.controller.app.company.vo.InviteReqVO;
import com.tradepass.module.identity.controller.app.company.vo.JoinReqVO;
import com.tradepass.module.identity.controller.app.company.vo.RoleReqVO;
import com.tradepass.module.identity.controller.app.company.vo.SealReqVO;
import com.tradepass.module.identity.controller.app.company.vo.VerificationReqVO;
import com.tradepass.module.identity.api.company.dto.InviteResult;
import com.tradepass.module.identity.api.company.dto.JoinResult;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyInviteMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.dataobject.permission.PermDefDO;
import com.tradepass.module.identity.api.permission.dto.RoleRespDTO;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompanyServiceTest {
    private CompanyMapper companyMapper;
    private CompanyMemberMapper memberMapper;
    private CompanyInviteMapper inviteMapper;
    private CounterpartyRelationMapper relationMapper;
    private RoleDefMapper roleMapper;
    private PermDefMapper permMapper;
    private AccessControlService accessControl;
    private CompanySearchRateLimiter searchRateLimiter;
    private AuditLogService auditLogService;
    private MemberRemovalNoticeService removalNoticeService;
    private CompanyService service;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(CompanyDO.class, CompanyMemberDO.class, RoleDefDO.class, CompanyInviteDO.class);
        companyMapper = mock(CompanyMapper.class);
        memberMapper = mock(CompanyMemberMapper.class);
        inviteMapper = mock(CompanyInviteMapper.class);
        relationMapper = mock(CounterpartyRelationMapper.class);
        roleMapper = mock(RoleDefMapper.class);
        permMapper = mock(PermDefMapper.class);
        accessControl = mock(AccessControlService.class);
        searchRateLimiter = mock(CompanySearchRateLimiter.class);
        auditLogService = mock(AuditLogService.class);
        removalNoticeService = mock(MemberRemovalNoticeService.class);
        service = new CompanyServiceImpl(companyMapper, memberMapper, inviteMapper, relationMapper,
                roleMapper, permMapper, accessControl, searchRateLimiter, new RolePermissionServiceImpl(),
                auditLogService, removalNoticeService, true);
        when(accessControl.requireCompanyProfileAccess(anyLong()))
                .thenReturn(AccessControlOperations.CompanyProfileAccess.SENSITIVE_OWNER);
        when(accessControl.hasPermission(anyLong(), any())).thenReturn(true);
        when(accessControl.effectiveRole(anyLong(), anyLong()))
                .thenReturn(new AccessControlOperations.EffectiveRole("ADMIN", "管理员", List.of("member_manage")));
        when(permMapper.selectById(any())).thenAnswer(invocation -> {
            PermDefDO permission = new PermDefDO();
            permission.setCode(invocation.getArgument(0));
            return permission;
        });
        when(memberMapper.update(any(Wrapper.class))).thenReturn(1);
        when(memberMapper.delete(any(Wrapper.class))).thenReturn(1);
        when(roleMapper.update(any(Wrapper.class))).thenReturn(1);
        when(inviteMapper.update(any(Wrapper.class))).thenReturn(1);
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void searchesPersistedCompaniesWithoutInventingFallbackData() {
        assertThat(service.searchCompanies("  ")).isEmpty();
        CompanyDO persisted = company(2L, "河北通瑞贸易有限公司");
        persisted.setCreditCode("91130100MA01234567");
        persisted.setContactPhone("13800000000");
        persisted.setBankAccount("6222021234567890123");
        when(companyMapper.selectList(any(Wrapper.class))).thenReturn(List.of(persisted));
        assertThat(service.searchCompanies("通瑞"))
                .extracting(CompanySearchSummary::name)
                .containsExactly("河北通瑞贸易有限公司");
        assertThat(service.searchCompanies("通瑞").get(0))
                .satisfies(summary -> {
                    assertThat(summary.maskedCreditCode()).isEqualTo("9113**********4567");
                    assertThat(summary.verified()).isTrue();
                });

        when(companyMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertThat(service.searchCompanies("全新企业")).isEmpty();

        assertThatThrownBy(() -> service.searchCompanies("企"))
                .hasMessage("搜索关键词至少需要 2 个字符");
        assertThatThrownBy(() -> service.searchCompanies("%%"))
                .hasMessage("搜索关键词格式不正确");
    }

    @Test
    void unfinishedOnboardingIsScopedToTheCreatorAndDoesNotRequireActiveMembership() {
        CompanyDO pending = company(9L, "待认证企业");
        pending.setCertificationStatus("PENDING_REVIEW");
        pending.setCreatedBy(7L);
        when(companyMapper.selectList(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CompanyDO>) invocation.getArgument(0);
            assertThat(query.getSqlSegment()).contains("created_by", "certification_status");
            assertThat(query.getParamNameValuePairs().values())
                    .contains(7L, "PENDING", "PENDING_REVIEW", "REJECTED")
                    .doesNotContain("VERIFIED");
            return List.of(pending);
        });

        assertThat(service.myOnboardingCompanies()).extracting(CompanyProfile::id).containsExactly("9");
        org.mockito.Mockito.verifyNoInteractions(memberMapper, accessControl);
    }

    @Test
    void returnsCompanyOrExplainsMissingCompany() {
        CompanyDO company = company(3L, "当前企业");
        when(companyMapper.selectById(3L)).thenReturn(company);
        assertThat(service.getCompany("3").name()).isEqualTo("当前企业");

        assertThatThrownBy(() -> service.getCompany("99"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("企业不存在");
    }

    @Test
    void masksCounterpartySensitiveFields() {
        CompanyDO company = company(9L, "合作企业");
        company.setContactPhone("13800001234");
        company.setBankName("测试银行");
        company.setBankAccount("6222021234567890123");
        when(companyMapper.selectById(9L)).thenReturn(company);
        when(accessControl.requireCompanyProfileAccess(9L))
                .thenReturn(AccessControlOperations.CompanyProfileAccess.COUNTERPARTY);

        CompanyProfile profile = service.getCompany("9");

        assertThat(profile.contactPhone()).isEqualTo("138****1234");
        assertThat(profile.bankAccount()).isEqualTo("***************0123");
        assertThat(profile.realNameStatus()).isNull();
        assertThat(profile.faceStatus()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitsNewAndExistingCompanyProfiles() {
        AtomicReference<CompanyDO> stored = new AtomicReference<>();
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(companyMapper.selectById(any())).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            CompanyDO inserted = invocation.getArgument(0);
            inserted.setId(10L);
            stored.set(inserted);
            return 1;
        }).when(companyMapper).insert(any(CompanyDO.class));

        CompanyProfile created = service.submitCompany(
                new CompanySubmitReqVO("10", "新企业", "NEW-CODE", "新法人"));
        assertThat(created.id()).isEqualTo("10");
        assertThat(created.certificationStatus()).isEqualTo("PENDING");
        verify(companyMapper).insert(any(CompanyDO.class));

        CompanyDO existing = company(11L, "旧名称");
        existing.setCreatedBy(7L);
        existing.setCertificationStatus("PENDING");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        when(companyMapper.selectById(11L)).thenReturn(existing);
        CompanyProfile updated = service.submitCompany(
                new CompanySubmitReqVO("11", "新名称", existing.getCreditCode(), "新法人"));
        assertThat(updated.name()).isEqualTo("新名称");
        verify(companyMapper).updateById(existing);
    }

    @Test
    void requiresVerifiedPersonBeforeCreatingCompany() {
        FadadaPersonalIdentityService personalIdentityService = mock(FadadaPersonalIdentityService.class);
        service.setPersonalIdentityService(personalIdentityService);
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(personalIdentityService.requireCurrentVerified())
                .thenThrow(new BusinessException("请先完成个人实名认证，再创建或认证企业"));

        assertThatThrownBy(() -> service.submitCompany(
                new CompanySubmitReqVO("10", "新企业", "NEW-CODE", "新法人")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("请先完成个人实名认证，再创建或认证企业");
    }

    @Test
    void verifiedCompanyCannotRenameItselfOrItsLegalRepresentative() {
        CompanyDO company = company(3L, "认证名称");
        company.setCreatedBy(7L);
        company.setLegalPersonName("认证法人");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(company);
        assertThatThrownBy(() -> service.submitCompany(new CompanySubmitReqVO(
                "3", "另一个名称", company.getCreditCode(), "认证法人"))).hasMessageContaining("不能直接修改");
        assertThatThrownBy(() -> service.submitCompany(new CompanySubmitReqVO(
                "3", "认证名称", company.getCreditCode(), "另一个法人"))).hasMessageContaining("不能直接修改");
        verify(companyMapper, org.mockito.Mockito.never()).updateById(any(CompanyDO.class));
        assertThat(company.getCertificationStatus()).isEqualTo("VERIFIED");
        assertThat(company.getName()).isEqualTo("认证名称");
    }

    @Test
    void verifiedCompanyCanUpdateContactInformationWithCurrentLegalPermission() {
        CompanyDO company = company(3L, "认证名称");
        company.setCreatedBy(7L); company.setLegalPersonName("认证法人");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(company);
        when(companyMapper.selectById(3L)).thenReturn(company);
        service.submitCompany(new CompanySubmitReqVO("3", "认证名称", company.getCreditCode(), "认证法人",
                "新地址", "13800138000", "银行", "123456"));
        verify(accessControl).requireLegal(3L);
        verify(companyMapper).updateById(company);
        assertThat(company.getRegisteredAddress()).isEqualTo("新地址");
    }

    @Test
    void verifiedLegalSuccessorCanMaintainCompanyOriginallyCreatedByAgent() {
        CompanyDO company = company(3L, "认证名称");
        company.setCreatedBy(99L); company.setLegalPersonName("已核验法人");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(company);
        when(companyMapper.selectById(3L)).thenReturn(company);
        service.submitCompany(new CompanySubmitReqVO("3", "认证名称", company.getCreditCode(), "已核验法人",
                "法人更新地址", "13800138000", "银行", "123456"));
        verify(accessControl).requireLegal(3L);
        assertThat(company.getCreatedBy()).isEqualTo(99L);
        assertThat(company.getRegisteredAddress()).isEqualTo("法人更新地址");
    }

    @Test
    void formerCreatorCannotEditVerifiedCompanyAfterLosingLegalRole() {
        CompanyDO company = company(3L, "认证名称"); company.setCreatedBy(7L);
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(company);
        org.mockito.Mockito.doThrow(new BusinessException("仅法人可操作")).when(accessControl).requireLegal(3L);
        assertThatThrownBy(() -> service.submitCompany(new CompanySubmitReqVO(
                "3", company.getName(), company.getCreditCode(), company.getLegalPersonName()))).hasMessage("仅法人可操作");
        verify(companyMapper, org.mockito.Mockito.never()).updateById(any(CompanyDO.class));
    }

    @Test
    void identityCannotChangeWhileCertificationIsPending() {
        CompanyDO company = company(3L, "审核中企业");
        company.setCreatedBy(7L); company.setCertificationStatus("PENDING_REVIEW");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(company);
        assertThatThrownBy(() -> service.submitCompany(new CompanySubmitReqVO(
                "3", "修改名称", company.getCreditCode(), "法人"))).hasMessageContaining("不能直接修改");
    }

    @Test
    void cooperationInvitationUsesCurrentCompanyEvenForMultiCompanyLegalUser() {
        AuthContext.set(7L, 8L);
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(invite(true));
        when(memberMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var wrapper = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CompanyMemberDO>) invocation.getArgument(0);
            // A query without the current-company condition would choose the user's other company.
            String sql = wrapper.getSqlSegment();
            CompanyMemberDO member = new CompanyMemberDO();
            member.setCompanyId(sql.contains("company_id =") && wrapper.getParamNameValuePairs().containsValue(8L) ? 8L : 5L);
            return member;
        });
        service.joinCompany(new JoinReqVO("PARTNER1"));
        ArgumentCaptor<CounterpartyRelationEntityDO> relation = ArgumentCaptor.forClass(CounterpartyRelationEntityDO.class);
        verify(relationMapper).insert(relation.capture());
        assertThat(relation.getValue().getCounterpartyCompanyId()).isEqualTo(8L);
    }

    @Test
    void selfInvitationAndFailedRelationInsertDoNotConsumeInvitation() {
        CompanyInviteDO invitation = invite(true);
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(invitation);
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("PARTNER1"))).hasMessageContaining("本企业");
        AuthContext.set(7L, 8L);
        CompanyMemberDO member = new CompanyMemberDO(); member.setCompanyId(8L);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("数据库异常"))
                .when(relationMapper).insert(any(CounterpartyRelationEntityDO.class));
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("PARTNER1"))).hasMessage("数据库异常");
        verify(inviteMapper, org.mockito.Mockito.never()).update(any(Wrapper.class));
        assertThat(invitation.getUsed()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesToExposeExistingCompanyThroughSubmission() {
        CompanyDO existing = company(11L, "已入驻企业");
        existing.setCreatedBy(99L);
        existing.setBankAccount("6222021234567890123");
        when(companyMapper.selectOne(any(Wrapper.class))).thenReturn(existing);
        org.mockito.Mockito.doThrow(new BusinessException("无权操作：仅法人可执行"))
                .when(accessControl).requireLegal(11L);

        assertThatThrownBy(() -> service.submitCompany(
                new CompanySubmitReqVO("11", "已入驻企业", existing.getCreditCode(), "法人")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权操作：仅法人可执行");
    }

    @Test
    void drivesCertificationAndSealStatusCommands() {
        CompanyDO company = company(3L, "当前企业");
        when(companyMapper.selectById(3L)).thenReturn(company);

        assertThat(service.submitCertification("3").id()).isEqualTo("3");
        assertThat(service.verifyRealName(new VerificationReqVO("3")).name()).isEqualTo("当前企业");
        assertThat(service.verifyFace(new VerificationReqVO("3")).name()).isEqualTo("当前企业");
        assertThat(service.uploadSeal(new SealReqVO("3", "https://files/seal.png", "公章")))
                .satisfies(seal -> {
                    assertThat(seal.id()).isNotBlank();
                    assertThat(seal.companyId()).isEqualTo("3");
                    assertThat(seal.id()).startsWith("MOCK-SEAL-");
                    assertThat(seal.status()).isEqualTo("UPLOADED");
                });
        assertThat(service.caMockEnabled()).isTrue();
        verify(companyMapper, org.mockito.Mockito.times(4)).update(any(Wrapper.class));
    }

    @Test
    void refusesSimulatedVerificationWhenProviderIsDisabled() {
        CompanyService productionService = new CompanyServiceImpl(companyMapper, memberMapper, inviteMapper, relationMapper,
                roleMapper, permMapper, accessControl, searchRateLimiter, new RolePermissionServiceImpl(),
                auditLogService, removalNoticeService, false);

        assertThatThrownBy(() -> productionService.verifyRealName(new VerificationReqVO("3")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("认证服务尚未配置，无法完成该操作");
        assertThatThrownBy(() -> productionService.uploadSeal(new SealReqVO("3", "dev://seal", "公章")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("认证服务尚未配置，无法完成该操作");
    }

    @Test
    void createsSingleUseMemberAndCounterpartyInvites() {
        InviteResult memberInvite = service.createInvite(new InviteReqVO("3", null));
        assertThat(memberInvite.code()).hasSize(8);
        verify(accessControl).requireManager(3L);

        InviteResult counterpartyInvite = service.createCounterpartyInvite(new InviteReqVO("3", "supplier"));
        assertThat(counterpartyInvite.code()).hasSize(8);
        verify(accessControl).requireLegal(3L);

        ArgumentCaptor<CompanyInviteDO> captor = ArgumentCaptor.forClass(CompanyInviteDO.class);
        verify(inviteMapper, org.mockito.Mockito.times(2)).insert(captor.capture());
        assertThat(captor.getAllValues()).extracting(CompanyInviteDO::getType)
                .containsExactly("member", "counterparty");
        assertThat(captor.getAllValues()).allSatisfy(invite -> {
            assertThat(invite.getUsed()).isFalse();
            assertThat(invite.getExpiresAt()).isAfter(LocalDateTime.now());
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsInvalidUsedAndExpiredInviteCodes() {
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("bad"))).hasMessage("邀请码无效");

        CompanyInviteDO used = invite(false);
        used.setUsed(true);
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(used);
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("used"))).hasMessage("邀请码已被使用");

        CompanyInviteDO expired = invite(false);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(expired);
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("expired"))).hasMessage("邀请码已过期");
    }

    @Test
    @SuppressWarnings("unchecked")
    void submitsMemberJoinForApprovalAndConsumesInvite() {
        CompanyInviteDO invite = invite(false);
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(invite);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        JoinResult result = service.joinCompany(new JoinReqVO("JOIN1234"));

        assertThat(result.status()).isEqualTo("PENDING");
        ArgumentCaptor<CompanyMemberDO> memberCaptor = ArgumentCaptor.forClass(CompanyMemberDO.class);
        verify(memberMapper).insert(memberCaptor.capture());
        assertThat(memberCaptor.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(memberCaptor.getValue().getUserId()).isEqualTo(7L);
        assertThat(memberCaptor.getValue().getRoleCode()).isEqualTo("GUEST");
        assertThat(invite.getUsed()).isTrue();
        assertThat(invite.getUsedBy()).isEqualTo(7L);
        verify(inviteMapper).update(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotDuplicateExistingMembershipApplications() {
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(invite(false));
        CompanyMemberDO active = new CompanyMemberDO();
        active.setStatus("ACTIVE");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(active);
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("JOIN1234")))
                .hasMessage("你已是该企业成员");

        active.setStatus("PENDING");
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("JOIN1234")))
                .hasMessage("申请已提交，等待审批");
    }

    @Test
    @SuppressWarnings("unchecked")
    void counterpartyInviteRequiresLegalMemberAndBuildsRelation() {
        AuthContext.set(7L, 8L);
        CompanyInviteDO invite = invite(true);
        invite.setRelationRole("supplier");
        when(inviteMapper.selectOne(any(Wrapper.class))).thenReturn(invite);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("PARTNER1")))
                .hasMessageContaining("仅公司法人");

        CompanyMemberDO legal = new CompanyMemberDO();
        legal.setCompanyId(8L);
        legal.setRoleCode("LEGAL");
        legal.setStatus("ACTIVE");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(legal);
        when(companyMapper.selectById(8L)).thenReturn(company(8L, "接受方"));
        when(companyMapper.selectById(3L)).thenReturn(company(3L, "邀请方"));

        JoinResult result = service.joinCompany(new JoinReqVO("PARTNER1"));

        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.message()).contains("客户关系");
        ArgumentCaptor<CounterpartyRelationEntityDO> captor = ArgumentCaptor.forClass(CounterpartyRelationEntityDO.class);
        verify(relationMapper).insert(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(8L);
        assertThat(captor.getValue().getCounterpartyCompanyId()).isEqualTo(3L);
        assertThat(captor.getValue().getCounterpartyCompanyName()).isEqualTo("邀请方");
    }

    @Test
    void approvesMembersAndManagesCustomRolesWithinTenant() {
        CompanyMemberDO pending = new CompanyMemberDO();
        pending.setId(12L);
        pending.setCompanyId(3L);
        pending.setUserId(8L);
        pending.setStatus("PENDING");
        RoleDefDO finance = new RoleDefDO();
        finance.setCompanyId(3L);
        finance.setCode("FINANCE");
        finance.setName("财务");
        finance.setPermissions("[\"invoice_view\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(finance);
        AuthorizationRecord approved = service.approveMember("12",
                new ApproveReqVO("FINANCE", List.of("order_view")), "3");
        assertThat(approved.status()).isEqualTo("ACTIVE");
        assertThat(approved.roleText()).isEqualTo("财务");
        verify(accessControl).requireManager(3L);
        verify(memberMapper).update(any(Wrapper.class));

        doAnswer(invocation -> {
            RoleDefDO role = invocation.getArgument(0);
            role.setId(15L);
            return 1;
        }).when(roleMapper).insert(any(RoleDefDO.class));
        RoleRespDTO role = service.createRole(new RoleReqVO("3", "审计", List.of("order_view")));
        assertThat(role.id()).isEqualTo("15");
        assertThat(role.name()).isEqualTo("审计");
        assertThat(role.code()).startsWith("CUSTOM_");
    }

    @Test
    void mapsMemberDisplayNamesAndSupportsScopedRemoval() {
        when(memberMapper.selectAuthorizationRecords(3L)).thenReturn(List.of(
                Map.of("id", 12L, "userId", 7L, "nickname", "新用户", "phone", "13800000000",
                        "roleCode", "ADMIN", "status", "ACTIVE"),
                Map.of("id", 13L, "userId", 8L, "nickname", "张三", "phone", "",
                        "roleCode", "FINANCE", "status", "PENDING")
        ));

        List<AuthorizationRecord> members = service.listMembers("3");
        assertThat(members).extracting(AuthorizationRecord::memberName)
                .containsExactly("用户0000", "张三");
        assertThat(members).extracting(AuthorizationRecord::roleText)
                .containsExactly("管理员", "财务");

        CompanyMemberDO removable = new CompanyMemberDO();
        removable.setId(12L);
        removable.setCompanyId(3L);
        removable.setUserId(8L);
        removable.setRoleCode("FINANCE");
        removable.setStatus("ACTIVE");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(removable);
        service.rejectMember("13", "3");
        service.removeMember("12", "3");
        verify(accessControl, org.mockito.Mockito.times(2)).requireManager(3L);
        verify(memberMapper, org.mockito.Mockito.times(2)).delete(any(Wrapper.class));
    }

    @Test
    void pagesMembersWithinTheResolvedTenant() {
        when(accessControl.resolveCompanyId("3")).thenReturn(3L);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(21L);
        when(memberMapper.selectAuthorizationPageRecords(3L, "ACTIVE", 10, 10L)).thenReturn(List.of(
                Map.of("id", 12L, "userId", 7L, "nickname", "张三", "phone", "13800000000",
                        "roleCode", "ADMIN", "status", "ACTIVE")
        ));

        var result = service.pageMembers("3", " ACTIVE ", 2, 10);

        assertThat(result.total()).isEqualTo(21);
        assertThat(result.hasMore()).isTrue();
        assertThat(result.items()).extracting(AuthorizationRecord::memberName).containsExactly("张三");
    }

    @Test
    void listsUpdatesAndDeletesRolesWithinOwningCompany() {
        when(accessControl.resolveCompanyId("3")).thenReturn(3L);
        RoleDefDO existing = new RoleDefDO();
        existing.setId(15L);
        existing.setCompanyId(3L);
        existing.setCode("CUSTOM_AUDIT");
        existing.setName("审计");
        existing.setPermissions("[\"order_view\"]");
        existing.setSystemRole(false);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(existing));
        assertThat(service.listRoles("3")).hasSize(1);

        RoleReqVO update = new RoleReqVO("3", "高级审计", List.of("order_view", "invoice_view"));
        when(roleMapper.selectById(15L)).thenReturn(existing);
        service.updateRole("15", update);
        verify(accessControl, org.mockito.Mockito.times(2)).requireManager(3L);
        verify(roleMapper).update(any(Wrapper.class));

        when(roleMapper.selectById(15L)).thenReturn(null);
        service.deleteRole("15");

        when(roleMapper.selectById(15L)).thenReturn(existing);
        service.deleteRole("15");
        verify(roleMapper).deleteById(15L);
    }

    @Test
    void protectsLegalIdentityWhileAllowingDefaultRolePermissionChanges() {
        CompanyMemberDO pending = new CompanyMemberDO();
        pending.setId(12L);
        pending.setCompanyId(3L);
        pending.setUserId(8L);
        pending.setStatus("PENDING");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(pending);
        assertThatThrownBy(() -> service.approveMember("12", new ApproveReqVO("LEGAL", List.of()), "3"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("该角色不能通过成员审批分配");

        RoleDefDO systemRole = new RoleDefDO();
        systemRole.setId(2L);
        systemRole.setCompanyId(3L);
        systemRole.setCode("ADMIN");
        systemRole.setSystemRole(true);
        when(roleMapper.selectById(2L)).thenReturn(systemRole);
        RoleReqVO request = new RoleReqVO("3", "管理员", List.of("member_manage"));
        service.updateRole("2", request);
        verify(roleMapper).update(any(Wrapper.class));
        systemRole.setCode("LEGAL");
        assertThatThrownBy(() -> service.updateRole("2", request)).hasMessage("法人及认证身份角色不可编辑");
        assertThatThrownBy(() -> service.deleteRole("2")).hasMessage("系统角色不可删除");
    }

    @Test
    void removesLastAdministratorButProtectsLegalAndCurrentMember() {
        CompanyMemberDO member = new CompanyMemberDO();
        member.setId(12L);
        member.setCompanyId(3L);
        member.setUserId(8L);
        member.setStatus("ACTIVE");
        member.setRoleCode("ADMIN");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        service.removeMember("12", "3");
        verify(memberMapper).delete(any(Wrapper.class));
        verify(removalNoticeService).recordRemoval(8L, 3L);
        verify(memberMapper, org.mockito.Mockito.never()).selectCount(any(Wrapper.class));
        member.setIsLegalPerson(true);
        assertThatThrownBy(() -> service.removeMember("12", "3"))
                .hasMessage("法人只能通过法人变更流程移交");
        member.setIsLegalPerson(false);
        member.setUserId(7L);
        assertThatThrownBy(() -> service.removeMember("12", "3"))
                .hasMessage("不能移除当前登录成员");
    }

    @Test
    void changesAdministratorToEmptyCustomRoleAndClearsLegacyGrants() {
        CompanyMemberDO member = new CompanyMemberDO();
        member.setId(12L);
        member.setCompanyId(3L);
        member.setUserId(8L);
        member.setStatus("ACTIVE");
        member.setRoleCode("ADMIN");
        member.setCustomPermissions("[\"member_manage\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        RoleDefDO role = new RoleDefDO();
        role.setCompanyId(3L);
        role.setCode("CUSTOM_READONLY");
        role.setPermissions("[]");
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(role);
        service.updateMemberRole("12", new ApproveReqVO("CUSTOM_READONLY", List.of()), "3");
        ArgumentCaptor<Wrapper> update = ArgumentCaptor.forClass(Wrapper.class);
        verify(memberMapper).update(update.capture());
        assertThat(update.getValue().getSqlSet()).contains("custom_permissions=", "is_administrator=", "role_code=");
        verify(accessControl).requireManager(3L);
        when(memberMapper.update(any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO("CUSTOM_READONLY", List.of()), "3"))
                .hasMessage("成员状态已变化，请刷新后重试");
        member.setIsLegalPerson(true);
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO("CUSTOM_READONLY", List.of()), "3"))
                .hasMessage("法人只能通过法人变更流程移交");
        member.setIsLegalPerson(false);
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO("LEGAL", List.of()), "3"))
                .hasMessage("该角色不能通过成员审批分配");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO("CUSTOM_READONLY", List.of()), "3"))
                .hasMessage("成员不存在或状态已变化");
    }

    @Test
    void allowsEmptyRolePermissionsButRejectsUnknownAndUnauthorizedGrants() {
        RoleDefDO role = new RoleDefDO();
        role.setId(2L);
        role.setCompanyId(3L);
        role.setCode("SALES");
        role.setName("销售员");
        role.setSystemRole(true);
        role.setPermissions("[]");
        when(roleMapper.selectById(2L)).thenReturn(role);
        service.updateRole("2", new RoleReqVO("3", "销售员", List.of()));
        when(accessControl.hasPermission(3L, "order_create")).thenReturn(false);
        assertThatThrownBy(() -> service.updateRole("2", new RoleReqVO("3", "销售员", List.of("order_create"))))
                .hasMessage("不能授予当前操作者不具备的权限 order_create");
        when(permMapper.selectById("unknown")).thenReturn(null);
        assertThatThrownBy(() -> service.updateRole("2", new RoleReqVO("3", "销售员", List.of("unknown"))))
                .hasMessage("未知权限 unknown");
        assertThatThrownBy(() -> service.updateRole("2", new RoleReqVO("3", "销售员", List.of("all"))))
                .hasMessage("不能配置保留权限 all");
        assertThatThrownBy(() -> service.updateRole("2", new RoleReqVO("4", "销售员", List.of())))
                .hasMessage("角色不存在");
        when(accessControl.resolveCompanyId("3")).thenReturn(3L);
        when(roleMapper.selectList(any(Wrapper.class))).thenReturn(List.of(role));
        RoleRespDTO payload = service.listRoles("3").get(0);
        assertThat(payload.editable()).isTrue();
        assertThat(payload.deletable()).isFalse();
    }

    @Test
    void assignsMultipleRolesAndValidatesEveryRoleBeforeWriting() {
        CompanyMemberDO target = new CompanyMemberDO();
        target.setId(12L);
        target.setUserId(8L);
        target.setCompanyId(3L);
        target.setStatus("PENDING");
        target.setRoleCode("GUEST");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(target);
        RoleDefDO sales = new RoleDefDO();
        sales.setCode("SALES");
        sales.setName("销售员");
        sales.setPermissions("[\"order_create\",\"contract_view\"]");
        RoleDefDO finance = new RoleDefDO();
        finance.setCode("FINANCE");
        finance.setName("财务");
        finance.setPermissions("[\"invoice_view\",\"contract_view\"]");
        when(roleMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RoleDefDO>) invocation.getArgument(0);
            query.getSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains(3L);
            if (query.getParamNameValuePairs().containsValue("SALES")) return sales;
            if (query.getParamNameValuePairs().containsValue("FINANCE")) return finance;
            return null;
        });
        var request = new ApproveReqVO(null, List.of(), List.of("SALES", "FINANCE", "SALES"));
        AuthorizationRecord approved = service.approveMember("12", request, "3");
        assertThat(approved.roles()).extracting(com.tradepass.framework.common.pojo.TradePassDtos.MemberRole::code)
                .containsExactly("SALES", "FINANCE");
        assertThat(approved.permissions()).containsExactlyInAnyOrder("order_create", "contract_view", "invoice_view");
        target.setStatus("ACTIVE");
        target.setRoleCode("SALES");
        target.setRoleCodes("[\"SALES\",\"FINANCE\"]");
        service.updateMemberRole("12", new ApproveReqVO(null, List.of(), List.of("FINANCE")), "3");
        ArgumentCaptor<Wrapper> updates = ArgumentCaptor.forClass(Wrapper.class);
        verify(memberMapper, org.mockito.Mockito.times(2)).update(updates.capture());
        var update = (com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CompanyMemberDO>) updates.getAllValues().get(1);
        assertThat(update.getSqlSet()).contains("role_codes=");
        assertThat(update.getParamNameValuePairs().values()).contains("[\"FINANCE\"]");
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO(null, List.of(), List.of()), "3"))
                .hasMessage("请至少选择一个角色");
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO(null, List.of(), List.of("SALES", "LEGAL")), "3"))
                .hasMessage("该角色不能通过成员审批分配");
        assertThatThrownBy(() -> service.updateMemberRole("12", new ApproveReqVO(null, List.of(), List.of("SALES", "OTHER_COMPANY_ROLE")), "3"))
                .hasMessage("角色不存在或不属于当前企业");
        when(accessControl.hasPermission(3L, "invoice_view")).thenReturn(false);
        assertThatThrownBy(() -> service.updateMemberRole("12", request, "3"))
                .hasMessage("不能授予当前操作者不具备的权限 invoice_view");
        verify(memberMapper, org.mockito.Mockito.times(2)).update(any(Wrapper.class));
    }

    private CompanyDO company(long id, String name) {
        CompanyDO company = new CompanyDO();
        company.setId(id);
        company.setName(name);
        company.setCreditCode("CODE-" + id);
        company.setLegalPersonName("法人");
        company.setCertificationStatus("VERIFIED");
        company.setRealNameStatus("VERIFIED");
        company.setFaceStatus("VERIFIED");
        company.setSealStatus("UPLOADED");
        return company;
    }

    private CompanyInviteDO invite(boolean counterparty) {
        CompanyInviteDO invite = new CompanyInviteDO();
        invite.setId(10L);
        invite.setCompanyId(3L);
        invite.setCode("JOIN1234");
        invite.setType(counterparty ? "counterparty" : "member");
        invite.setUsed(false);
        invite.setExpiresAt(LocalDateTime.now().plusHours(1));
        return invite;
    }
}
