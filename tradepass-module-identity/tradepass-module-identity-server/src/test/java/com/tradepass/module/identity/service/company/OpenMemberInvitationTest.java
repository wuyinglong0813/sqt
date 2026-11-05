package com.tradepass.module.identity.service.company;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.audit.core.AuditLogService;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.controller.app.company.vo.ApproveReqVO;
import com.tradepass.module.identity.controller.app.company.vo.InviteReqVO;
import com.tradepass.module.identity.controller.app.company.vo.JoinReqVO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyInviteMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.module.identity.service.notice.MemberRemovalNoticeService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionServiceImpl;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenMemberInvitationTest {
    private CompanyMapper companies;
    private CompanyMemberMapper members;
    private CompanyInviteMapper invites;
    private SysUserMapper users;
    private RoleDefMapper roles;
    private AccessControlService access;
    private FadadaPersonalIdentityService identity;
    private CompanyServiceImpl service;
    private CompanyInviteDO invitation;
    private SysUserDO applicant;

    @BeforeEach void setUp() {
        MybatisTestSupport.initialize(CompanyDO.class, CompanyMemberDO.class, CompanyInviteDO.class,
                SysUserDO.class, RoleDefDO.class);
        companies = mock(CompanyMapper.class);
        members = mock(CompanyMemberMapper.class);
        invites = mock(CompanyInviteMapper.class);
        users = mock(SysUserMapper.class);
        roles = mock(RoleDefMapper.class);
        access = mock(AccessControlService.class);
        identity = mock(FadadaPersonalIdentityService.class);
        service = new CompanyServiceImpl(companies, members, invites, mock(CounterpartyRelationMapper.class),
                roles, mock(PermDefMapper.class), access, mock(CompanySearchRateLimiter.class),
                new RolePermissionServiceImpl(), mock(AuditLogService.class), mock(MemberRemovalNoticeService.class),
                mock(FadadaCorpIdentityMapper.class), mock(CompanyCertificationApplicationMapper.class), false);
        service.setInviteUserMapper(users);
        service.setPersonalIdentityService(identity);
        AuthContext.set(7L, null);
        applicant = user(7L);
        when(users.selectById(anyLong())).thenAnswer(inv -> user(inv.getArgument(0)));
        when(users.selectById(7L)).thenAnswer(inv -> applicant);
        invitation = invitation();
        when(invites.selectOne(any(Wrapper.class))).thenReturn(invitation);
        when(members.update(any(Wrapper.class))).thenReturn(1);
        when(members.insert(any(CompanyMemberDO.class))).thenAnswer(inv -> {
            CompanyMemberDO row = inv.getArgument(0); row.setId(101L); return 1;
        });
        CompanyDO company = new CompanyDO(); company.setId(3L); company.setName("受邀企业");
        company.setBankAccount("must-not-be-exposed");
        when(companies.selectById(3L)).thenReturn(company);
        RoleDefDO role = new RoleDefDO(); role.setCompanyId(3L); role.setCode("SALES");
        role.setName("销售员"); role.setPermissions("[]");
        when(roles.selectOne(any(Wrapper.class))).thenReturn(role);
    }

    @AfterEach void clear() { AuthContext.clear(); }

    @Test void createsExplicitOpenEntranceWithoutPhoneAndWithExpiry() {
        var result = service.createInvite(new InviteReqVO("3", null));
        ArgumentCaptor<CompanyInviteDO> saved = ArgumentCaptor.forClass(CompanyInviteDO.class);
        verify(invites).insert(saved.capture());
        assertThat(saved.getValue().getInvitationMode()).isEqualTo("OPEN");
        assertThat(saved.getValue().getTargetPhoneHash()).isNull();
        assertThat(saved.getValue().getInvitedBy()).isEqualTo(7L);
        assertThat(result.expiresAt()).isAfter(LocalDateTime.now().plusHours(23));
        verify(access).requireManager(3L);
    }

    @Test void unverifiedPhoneAndIdentityCanRecordApplicationWithoutCreatingAnActiveMember() {
        applicant.setPhone(null); applicant.setPhoneVerifiedAt(null);
        var result = service.joinCompany(new JoinReqVO("OPEN1234"));
        assertThat(result.status()).isEqualTo("PENDING_IDENTITY");
        assertThat(result.applicationId()).isEqualTo("101");
        assertThat(result.companyName()).isEqualTo("受邀企业");
        ArgumentCaptor<CompanyMemberDO> saved = ArgumentCaptor.forClass(CompanyMemberDO.class);
        verify(members).insert(saved.capture());
        CompanyMemberDO row = saved.getValue();
        assertThat(row.getRoleCode()).isEqualTo("GUEST");
        assertThat(row.getRoleCodes()).isNull();
        assertThat(row.getCustomPermissions()).isNull();
        assertThat(row.getIsAdministrator()).isFalse();
        assertThat(row.getInviteId()).isEqualTo(10L);
        assertThat(row.getInvitedBy()).isEqualTo(8L);
        verify(invites, never()).update(any(Wrapper.class));
        verify(identity, never()).requireCurrentVerified();
    }

    @Test void verifiedApplicantStillNeedsAdministratorApproval() {
        when(identity.current()).thenReturn(verified());
        when(identity.requireCurrentVerified()).thenReturn(verified());
        var result = service.joinCompany(new JoinReqVO("OPEN1234"));
        assertThat(result.status()).isEqualTo("PENDING");
        verify(identity).requireCurrentVerified();
        verify(invites, never()).update(any(Wrapper.class));
    }

    @Test void anotherPersonMayApplyWithoutConsumingSharedInvitation() {
        List<CompanyMemberDO> applications = new ArrayList<>();
        when(members.insert(any(CompanyMemberDO.class))).thenAnswer(inv -> {
            CompanyMemberDO row = inv.getArgument(0); row.setId(100L + applications.size()); applications.add(row); return 1;
        });
        service.joinCompany(new JoinReqVO("OPEN1234"));
        AuthContext.set(9L, null);
        service.joinCompany(new JoinReqVO("OPEN1234"));
        assertThat(applications).extracting(CompanyMemberDO::getUserId).containsExactly(7L, 9L);
        assertThat(applications).extracting(CompanyMemberDO::getStatus).containsOnly("PENDING_IDENTITY");
        assertThat(invitation.getUsed()).isFalse();
        verify(invites, never()).update(any(Wrapper.class));
    }

    @Test void repeatApplicationReturnsSameApplicationWithoutDuplicateInsert() {
        CompanyMemberDO existing = pending("PENDING_IDENTITY");
        when(members.selectOne(any(Wrapper.class))).thenReturn(existing);
        var result = service.joinCompany(new JoinReqVO("OPEN1234"));
        assertThat(result.applicationId()).isEqualTo("101");
        assertThat(result.status()).isEqualTo("PENDING_IDENTITY");
        verify(members, never()).insert(any(CompanyMemberDO.class));
        verify(users).lockForMembership(7L);
    }

    @Test void openEntranceDoesNotReviveHistoricalUntargetedCodesOrBypassExpiry() {
        invitation.setInvitationMode("LEGACY");
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("OPEN1234")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46203));
        invitation.setInvitationMode("OPEN"); invitation.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        assertThatThrownBy(() -> service.joinCompany(new JoinReqVO("OPEN1234"))).hasMessage("邀请码已过期");
        verify(members, never()).insert(any(CompanyMemberDO.class));
    }

    @Test void publicPreviewReturnsCompanyNameAndRequirementsWithoutMembership() {
        applicant.setPhoneVerifiedAt(null);
        var preview = service.previewInvite("OPEN1234");
        assertThat(preview.invitationMode()).isEqualTo("OPEN");
        assertThat(preview.companyName()).isEqualTo("受邀企业");
        assertThat(preview.personalIdentityRequired()).isTrue();
        assertThat(preview.phoneBindingRequired()).isTrue();
        assertThat(preview.toString()).doesNotContain("must-not-be-exposed");
        verify(members, never()).insert(any(CompanyMemberDO.class));
    }

    @Test void ownApplicationProgressIsLimitedToApplicantAndContainsNoBusinessProfile() {
        when(members.selectList(any(Wrapper.class))).thenReturn(List.of(pending("PENDING_IDENTITY")));
        var result = service.myMemberApplications();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).personalIdentityRequired()).isTrue();
        assertThat(result.get(0).toString()).doesNotContain("must-not-be-exposed");
        ArgumentCaptor<Wrapper> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(members).selectList(query.capture());
        LambdaQueryWrapper<CompanyMemberDO> wrapper = (LambdaQueryWrapper<CompanyMemberDO>) query.getValue();
        wrapper.getSqlSegment();
        assertThat(wrapper.getSqlSegment()).contains("user_id", "status", "role_code");
        assertThat(wrapper.getParamNameValuePairs().values()).contains(7L, "GUEST", "PENDING_IDENTITY", "PENDING");
    }

    @Test void realNameCompletionRechecksServerIdentityBeforeMovingOnlyToPending() {
        CompanyMemberDO application = pending("PENDING_IDENTITY");
        when(members.selectOne(any(Wrapper.class))).thenReturn(application);
        when(identity.requireCurrentVerified()).thenThrow(new BusinessException("尚未实名"));
        assertThatThrownBy(() -> service.markMemberApplicationIdentityReady("101")).hasMessage("尚未实名");
        verify(members, never()).update(any(Wrapper.class));
        doReturn(verified()).when(identity).requireCurrentVerified();
        assertThat(service.markMemberApplicationIdentityReady("101").status()).isEqualTo("PENDING");
        ArgumentCaptor<Wrapper> changed = ArgumentCaptor.forClass(Wrapper.class);
        verify(members).update(changed.capture());
        LambdaUpdateWrapper<CompanyMemberDO> update = (LambdaUpdateWrapper<CompanyMemberDO>) changed.getValue();
        update.getSqlSegment();
        assertThat(update.getParamNameValuePairs().values()).contains(7L, "PENDING_IDENTITY", "PENDING").doesNotContain("ACTIVE");
    }

    @Test void anotherAccountsApplicationCannotBeCompleted() {
        when(members.selectOne(any(Wrapper.class))).thenAnswer(inv -> {
            LambdaQueryWrapper<CompanyMemberDO> query = inv.getArgument(0); query.getSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains(7L, 999L);
            assertThat(query.getSqlSegment()).contains("user_id");
            return null;
        });
        assertThatThrownBy(() -> service.markMemberApplicationIdentityReady("999")).hasMessageContaining("申请不存在");
        verify(members, never()).update(any(Wrapper.class));
    }

    @Test void unverifiedPhoneCannotAdvanceEvenWhenRealNameIsVerified() {
        when(members.selectOne(any(Wrapper.class))).thenReturn(pending("PENDING_IDENTITY"));
        applicant.setPhoneVerifiedAt(null);
        when(identity.requireCurrentVerified()).thenReturn(verified());
        assertThatThrownBy(() -> service.markMemberApplicationIdentityReady("101"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46201));
        verify(members, never()).update(any(Wrapper.class));
    }

    @Test void administratorCannotApproveUnverifiedOrWaitingIdentityApplicant() {
        CompanyMemberDO application = pending("PENDING_IDENTITY");
        when(members.selectOne(any(Wrapper.class))).thenReturn(application);
        assertThatThrownBy(() -> service.approveMember("101", new ApproveReqVO("SALES", List.of()), "3"))
                .hasMessageContaining("状态已变化");
        application.setStatus("PENDING");
        // The administrator's own identity is verified; the applicant's isn't.
        AuthContext.set(8L, 3L);
        when(identity.verifiedOpenUserId(8L)).thenReturn("verified-admin");
        assertThatThrownBy(() -> service.approveMember("101", new ApproveReqVO("SALES", List.of()), "3"))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(46103));
        verify(identity).verifiedOpenUserId(7L);
        verify(members, never()).update(any(Wrapper.class));
    }

    @Test void verifiedApprovalActivatesOnlyAfterAdministratorAssignsRole() {
        CompanyMemberDO application = pending("PENDING");
        when(members.selectOne(any(Wrapper.class))).thenReturn(application);
        AuthContext.set(8L, 3L);
        when(identity.verifiedOpenUserId(7L)).thenReturn("verified-applicant");
        var result = service.approveMember("101", new ApproveReqVO("SALES", List.of()), "3");
        assertThat(result.status()).isEqualTo("ACTIVE");
        assertThat(result.roleCode()).isEqualTo("SALES");
        verify(access).requireManager(3L);
        verify(identity).verifiedOpenUserId(7L);
        verify(members).update(any(Wrapper.class));
    }

    @Test void revocationRequiresManagerAndInvalidatesOnlyInvitation() {
        service.revokeInvite("OPEN1234");
        verify(access).requireManager(3L);
        verify(invites).update(any(Wrapper.class));
        verify(members, never()).update(any(Wrapper.class));
        doThrow(new BusinessException("无权操作")).when(access).requireManager(3L);
        clearInvocations(invites);
        assertThatThrownBy(() -> service.revokeInvite("OPEN1234")).hasMessage("无权操作");
        verify(invites, never()).update(any(Wrapper.class));
    }

    private CompanyMemberDO pending(String status) {
        CompanyMemberDO row = new CompanyMemberDO(); row.setId(101L); row.setCompanyId(3L); row.setUserId(7L);
        row.setStatus(status); row.setRoleCode("GUEST"); row.setInviteId(10L); row.setInvitedBy(8L);
        return row;
    }

    private CompanyInviteDO invitation() {
        CompanyInviteDO row = new CompanyInviteDO(); row.setId(10L); row.setCompanyId(3L); row.setCode("OPEN1234");
        row.setType("member"); row.setInvitationMode("OPEN"); row.setInvitedBy(8L); row.setUsed(false);
        row.setExpiresAt(LocalDateTime.now().plusHours(24)); return row;
    }

    private SysUserDO user(long id) {
        SysUserDO row = new SysUserDO(); row.setId(id); row.setPhone("13800000000"); row.setStatus("ACTIVE");
        row.setPhoneVerifiedAt(LocalDateTime.now()); return row;
    }

    private PersonalIdentityRespDTO verified() {
        return new PersonalIdentityRespDTO(true, "VERIFIED", "已认证", null, null, null,
                "已核实名成员", null, null, null, null, null);
    }
}
