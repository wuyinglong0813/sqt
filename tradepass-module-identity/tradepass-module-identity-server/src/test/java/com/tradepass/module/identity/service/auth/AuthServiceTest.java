package com.tradepass.module.identity.service.auth;

import com.tradepass.module.identity.service.permission.RolePermissionServiceImpl;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionService;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.LoginSession;
import com.tradepass.framework.common.pojo.TradePassDtos.MePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.UserProfile;
import com.tradepass.module.identity.controller.app.auth.vo.BindCompanyReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.BindPhoneReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.SwitchCompanyReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.SwitchUserReqVO;
import com.tradepass.module.identity.controller.app.auth.vo.WechatLoginReqVO;
import com.tradepass.module.trade.api.document.dto.TodoItem;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.trade.api.document.dto.BusinessDocumentRespDTO;
import com.tradepass.module.identity.dal.dataobject.permission.PermDefDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.trade.api.document.DocumentTodoReader;
import com.tradepass.module.trade.api.document.DocumentTodoReader.*;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceTest {
    private SysUserMapper userMapper;
    private CompanyMapper companyMapper;
    private CompanyMemberMapper memberMapper;
    private PermDefMapper permissionMapper;
    private ContractReader contractMapper;
    private DocumentTodoReader businessDocumentMapper;
    private WechatService wechatService;
    private AccessControlService accessControlService;
    private AuthSessionService sessionService;
    private ExperienceTestAccountService experienceTestAccountService;

    @BeforeEach
    void setUp() {
        MybatisTestSupport.initialize(SysUserDO.class, CompanyMemberDO.class, PermDefDO.class, BusinessDocumentRespDTO.class);
        userMapper = mock(SysUserMapper.class);
        when(userMapper.update(any(Wrapper.class))).thenReturn(1);
        companyMapper = mock(CompanyMapper.class);
        memberMapper = mock(CompanyMemberMapper.class);
        permissionMapper = mock(PermDefMapper.class);
        contractMapper = mock(ContractReader.class);
        businessDocumentMapper = mock(DocumentTodoReader.class);
        wechatService = mock(WechatService.class);
        accessControlService = mock(AccessControlService.class);
        sessionService = mock(AuthSessionService.class);
        experienceTestAccountService = mock(ExperienceTestAccountService.class);
        when(experienceTestAccountService.provisionIfConfigured(any(SysUserDO.class), any())).thenReturn(null);
        when(memberMapper.selectUserCompanies(anyLong())).thenReturn(List.of());
        when(accessControlService.effectiveRole(anyLong(), anyLong()))
                .thenReturn(new AccessControlOperations.EffectiveRole("ADMIN", "管理员", List.of("member_manage")));
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void productionLoginRejectsUnverifiedRawPhone() {
        AuthService service = service(false);
        when(wechatService.resolveOpenid("code", null)).thenReturn("openid");

        assertThatThrownBy(() -> service.wechatLogin(
                new WechatLoginReqVO("code", "昵称", null, "13800000000", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("生产环境不接受未经验证的手机号");
        verify(userMapper, never()).insert(any(SysUserDO.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsGuestUserAndIssuesRandomSession() {
        AuthService service = service(true);
        when(wechatService.resolveOpenid("dev-new", null)).thenReturn("dev-new");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        doAnswer(invocation -> {
            SysUserDO user = invocation.getArgument(0);
            user.setId(21L);
            return 1;
        }).when(userMapper).insert(any(SysUserDO.class));
        when(sessionService.issue(21L)).thenReturn("session-token");

        LoginSession result = service.wechatLogin(
                new WechatLoginReqVO("dev-new", "新用户", null, "13800000000", null));

        assertThat(result.token()).isEqualTo("session-token");
        assertThat(result.user().id()).isEqualTo("21");
        assertThat(result.user().currentRole()).isEqualTo("GUEST");
        assertThat(result.user().phone()).isEqualTo("13800000000");
    }

    @Test
    @SuppressWarnings("unchecked")
    void updatesExistingUserAndRestoresCompanyRole() {
        AuthService service = service(true);
        when(wechatService.resolveOpenid("dev-phone-login", null)).thenReturn("dev-phone-login");
        SysUserDO user = new SysUserDO();
        user.setId(7L);
        user.setOpenid("old-openid");
        user.setNickname("旧昵称");
        user.setPhone("13800000000");
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(user);
        when(memberMapper.selectUserCompanies(7L)).thenReturn(List.of(
                Map.of("companyId", 3L, "companyName", "测试企业", "roleCode", "ADMIN")));
        when(memberMapper.selectMemberInfoAnyCompany(7L)).thenReturn(memberRow(7L, "ADMIN", "ACTIVE"));
        when(sessionService.issue(7L)).thenReturn("token");

        LoginSession result = service.wechatLogin(
                new WechatLoginReqVO("dev-phone-login", "新昵称", null, "13800000000", null));

        assertThat(result.user().nickname()).isEqualTo("新昵称");
        assertThat(result.user().currentCompanyId()).isEqualTo("3");
        assertThat(result.user().currentRole()).isEqualTo("ADMIN");
        verify(userMapper).updateById(user);
    }

    @Test
    void bindsPhoneOnlyInDevelopmentAndReturnsMemberProfile() {
        AuthContext.set(7L, 3L);
        assertThatThrownBy(() -> service(false).bindPhone(new BindPhoneReqVO("13800000000")))
                .hasMessage("生产环境不接受未经验证的手机号");

        AuthService devService = service(true);
        when(userMapper.selectById(7L)).thenReturn(phoneBindingUser());
        when(memberMapper.selectMemberInfo(7L, 3L)).thenReturn(memberRow(7L, "FINANCE", "ACTIVE"));
        UserProfile profile = devService.bindPhone(new BindPhoneReqVO("13800000000"));

        assertThat(profile.currentCompanyId()).isEqualTo("3");
        assertThat(profile.currentRole()).isEqualTo("FINANCE");
        verify(userMapper).update(any(Wrapper.class));
    }

    @Test
    void guestBindsVerifiedWechatPhoneInProductionWithoutJoiningCompany() {
        AuthContext.set(7L, null);
        when(userMapper.selectById(7L)).thenReturn(phoneBindingUser());
        when(wechatService.resolvePhoneByCode("trusted-code")).thenReturn("13800000000");

        UserProfile result = service(false).bindPhone(new BindPhoneReqVO(null, "trusted-code"));

        assertThat(result.id()).isEqualTo("7");
        assertThat(result.openid()).isEqualTo("real-openid");
        assertThat(result.nickname()).isEqualTo("电脑用户");
        assertThat(result.phone()).isEqualTo("13800000000");
        assertThat(result.currentCompanyId()).isNull();
        assertThat(result.currentRole()).isEqualTo("GUEST");
        verify(wechatService).resolvePhoneByCode("trusted-code");
        verify(userMapper).update(any(Wrapper.class));
    }

    @Test
    void rejectedWechatPhoneCodeCannotWritePhone() {
        AuthContext.set(7L, null);
        when(wechatService.resolvePhoneByCode("expired-code"))
                .thenThrow(new BusinessException("微信凭证已失效"));
        assertThatThrownBy(() -> service(false).bindPhone(new BindPhoneReqVO(null, "expired-code")))
                .hasMessage("微信凭证已失效");
        verify(userMapper, never()).update(any(Wrapper.class));
    }

    @Test
    void productionBindingRequiresVerifiedCodeAndRejectsConcurrentPhoneReplacement() {
        AuthContext.set(7L, null);
        assertThatThrownBy(() -> service(false).bindPhone(new BindPhoneReqVO(null, null)))
                .hasMessage("请使用微信手机号验证完成绑定");
        when(userMapper.selectById(7L)).thenReturn(phoneBindingUser());
        when(wechatService.resolvePhoneByCode("trusted-code")).thenReturn("13800000000");
        when(userMapper.update(any(Wrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service(false).bindPhone(new BindPhoneReqVO(null, "trusted-code")))
                .hasMessage("账号手机号状态已变化，请刷新后重试");
    }

    @Test
    void bindingDoesNotMergePhoneOwnedByAnotherAccount() {
        AuthContext.set(7L, null);
        when(userMapper.selectById(7L)).thenReturn(phoneBindingUser());
        when(wechatService.resolvePhoneByCode("trusted-code")).thenReturn("13800000000");
        SysUserDO other = phoneBindingUser();
        other.setId(8L);
        when(userMapper.selectOne(any(Wrapper.class))).thenReturn(other);
        assertThatThrownBy(() -> service(false).bindPhone(new BindPhoneReqVO(null, "trusted-code")))
                .hasMessageContaining("已绑定其他账号");
        verify(userMapper, never()).update(any(Wrapper.class));
        verify(sessionService, never()).issue(anyLong());
    }

    @Test
    void bindingCannotReplaceAnExistingVerifiedPhone() {
        AuthContext.set(7L, null);
        SysUserDO user = phoneBindingUser();
        user.setPhone("13900000000");
        when(userMapper.selectById(7L)).thenReturn(user);
        when(wechatService.resolvePhoneByCode("trusted-code")).thenReturn("13800000000");
        assertThatThrownBy(() -> service(false).bindPhone(new BindPhoneReqVO(null, "trusted-code")))
                .hasMessageContaining("变更手机号请联系管理员");
        verify(userMapper, never()).update(any(Wrapper.class));
    }

    private SysUserDO phoneBindingUser() {
        SysUserDO user = new SysUserDO();
        user.setId(7L);
        user.setOpenid("real-openid");
        user.setNickname("电脑用户");
        user.setStatus("ACTIVE");
        return user;
    }

    @Test
    void buildsManagerTodosAndReturnsNoneOutsideCompanyOrRole() {
        AuthService service = service(true);
        AuthContext.set(7L, null);
        assertThat(service.myTodos()).isEmpty();

        AuthContext.set(7L, 3L);
        when(accessControlService.hasPermission(3L, "member_manage")).thenReturn(false);
        when(accessControlService.hasPermission(3L, "auth_manage")).thenReturn(false);
        assertThat(service.myTodos()).isEmpty();

        when(accessControlService.hasPermission(3L, "member_manage")).thenReturn(true);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        CompanyDO company = company(3L, "当前企业", 7L);
        company.setCertificationStatus("PENDING_REVIEW");
        when(companyMapper.selectById(3L)).thenReturn(company);
        when(accessControlService.hasPermission(3L, "contract_sign")).thenReturn(true);
        when(contractMapper.countContractsAwaitingSignature(3L)).thenReturn(3L);

        List<TodoItem> todos = service.myTodos();
        assertThat(todos).extracting(TodoItem::type)
                .containsExactly("APPROVAL", "CERT", "CONTRACT");
        assertThat(todos).extracting(TodoItem::count).containsExactly(2, 1, 3);
    }

    @Test
    void notifiesRecipientWhenSalesOrderNeedsConfirmation() {
        AuthService service = service(true);
        AuthContext.set(7L, 3L);
        when(accessControlService.hasPermission(3L, "member_manage")).thenReturn(false);
        when(accessControlService.hasPermission(3L, "auth_manage")).thenReturn(false);
        when(accessControlService.hasPermission(3L, "sales_order_receive")).thenReturn(true);
        when(businessDocumentMapper.pendingDocumentCount(anyLong())).thenReturn(2L);
        BusinessDocumentRespDTO document = new BusinessDocumentRespDTO();
        document.setId(44L);
        when(businessDocumentMapper.latestPendingDocument(anyLong())).thenReturn(document);

        List<TodoItem> todos = service.myTodos();

        assertThat(todos).hasSize(1);
        assertThat(todos.get(0).type()).isEqualTo("SALES_ORDER");
        assertThat(todos.get(0).count()).isEqualTo(2);
        assertThat(todos.get(0).target()).isEqualTo("sales-order-detail:44");
    }

    @Test
    void contractSignerGetsOwnSigningTodoWithoutMemberManagementPermission() {
        AuthContext.set(7L, 3L);
        when(accessControlService.hasPermission(3L, "contract_sign")).thenReturn(true);
        when(contractMapper.countContractsAwaitingSignature(3L)).thenReturn(1L);
        List<TodoItem> todos = service(false).myTodos();
        assertThat(todos).extracting(TodoItem::type).containsExactly("CONTRACT");
        assertThat(todos.get(0).target()).isEqualTo("contract-approval");
    }

    @Test
    void preventsSwitchingIntoAnotherTenant() {
        AuthContext.set(7L, 3L);
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service(true).switchCompany(new SwitchCompanyReqVO("9")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("你不是该公司成员");
        assertThatThrownBy(() -> service(true).switchCompany(new SwitchCompanyReqVO("bad")))
                .hasMessage("ID 格式不正确");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bindsOnlyMatchingCompanyOwnedByCurrentUser() {
        AuthContext.set(7L, null);
        AuthService service = service(true);
        BindCompanyReqVO request = new BindCompanyReqVO("3", "测试企业", "CODE3", "法人",
                null, null, null, null);
        when(companyMapper.selectById(3L)).thenReturn(null);
        assertThatThrownBy(() -> service.bindCompany(request)).hasMessage("企业不存在，请先提交企业资料");

        CompanyDO company = company(3L, "另一名称", 7L);
        when(companyMapper.selectById(3L)).thenReturn(company);
        assertThatThrownBy(() -> service.bindCompany(request)).hasMessage("企业资料与已提交记录不一致");

        company.setName("测试企业");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        when(memberMapper.selectUserCompanies(7L)).thenReturn(List.of());
        when(memberMapper.selectMemberInfo(7L, 3L)).thenReturn(memberRow(7L, "LEGAL_CANDIDATE", "PENDING"));

        MePayload result = service.bindCompany(request);
        assertThat(result.member().roleCode()).isEqualTo("LEGAL_CANDIDATE");
        verify(memberMapper).insert(any(CompanyMemberDO.class));
    }

    @Test
    void supportsFallbackProfileDevUsersLogoutAndUserSwitch() {
        AuthService service = service(true);
        AuthContext.set(7L, null);
        when(memberMapper.selectUserCompanies(7L)).thenReturn(List.of());
        when(memberMapper.selectMemberInfo(7L, 0L)).thenReturn(null);
        SysUserDO user = new SysUserDO();
        user.setNickname("访客");
        user.setPhone("13800000000");
        when(userMapper.selectById(7L)).thenReturn(user);
        assertThat(service.me().user().currentRole()).isEqualTo("GUEST");
        assertThat(service.me().company().name()).isEqualTo("未加入企业");

        when(memberMapper.selectDevUsers()).thenReturn(List.of(
                Map.of("id", 7L, "nickname", "管理员", "phone", "13800000000", "roleCode", "ADMIN")));
        assertThat(service.listDevUsers().get(0).roleText()).isEqualTo("管理员");

        when(memberMapper.selectMemberInfoAnyCompany(7L)).thenReturn(memberRow(7L, "ADMIN", "ACTIVE"));
        when(sessionService.issue(7L)).thenReturn("switched-token");
        LoginSession switched = service.switchUser(new SwitchUserReqVO("7"));
        assertThat(switched.token()).isEqualTo("switched-token");

        service.logout("Bearer switched-token");
        verify(sessionService).revoke("Bearer switched-token");
    }

    private AuthService service(boolean devEnabled) {
        return new AuthServiceImpl(userMapper, companyMapper, memberMapper, permissionMapper, contractMapper,
                businessDocumentMapper, wechatService, new RolePermissionServiceImpl(), accessControlService, sessionService,
                experienceTestAccountService, devEnabled);
    }

    private Map<String, Object> memberRow(long userId, String role, String status) {
        return Map.of(
                "userId", userId,
                "nickname", "测试用户",
                "phone", "13800000000",
                "roleCode", role,
                "status", status
        );
    }

    private CompanyDO company(long id, String name, long createdBy) {
        CompanyDO company = new CompanyDO();
        company.setId(id);
        company.setName(name);
        company.setCreditCode("CODE3");
        company.setLegalPersonName("法人");
        company.setCreatedBy(createdBy);
        company.setCertificationStatus("VERIFIED");
        company.setRealNameStatus("VERIFIED");
        company.setFaceStatus("VERIFIED");
        company.setSealStatus("UPLOADED");
        return company;
    }
}
