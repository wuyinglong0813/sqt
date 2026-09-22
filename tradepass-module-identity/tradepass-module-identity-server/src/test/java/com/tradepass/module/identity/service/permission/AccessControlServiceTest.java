package com.tradepass.module.identity.service.permission;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.tradepass.support.MybatisTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccessControlServiceTest {
    private CompanyMemberMapper memberMapper;
    private CounterpartyRelationMapper relationMapper;
    private RoleDefMapper roleMapper;
    private AccessControlService service;

    @BeforeEach
    void setUp() {
        memberMapper = mock(CompanyMemberMapper.class);
        relationMapper = mock(CounterpartyRelationMapper.class);
        roleMapper = mock(RoleDefMapper.class);
        service = new AccessControlServiceImpl(memberMapper, relationMapper, roleMapper,
                new RolePermissionServiceImpl(), new ObjectMapper());
        AuthContext.set(7L, 3L);
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolvesContextOrVerifiedRequestedCompany() {
        assertThat(service.resolveCompanyId(null)).isEqualTo(3L);
        assertThat(service.resolveCompanyId(" ")).isEqualTo(3L);

        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThat(service.resolveCompanyId("9")).isEqualTo(9L);
    }

    @Test
    void administratorCanContinueCertificationButCannotUseLegalOnlyActions() {
        MybatisTestSupport.initialize(CompanyMemberDO.class, RoleDefDO.class);
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeMember("ADMIN", null));
        assertThatCode(() -> service.requireCertificationOperator(3L)).doesNotThrowAnyException();
        assertThatCode(() -> service.requirePermission(3L, "seal_manage")).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireLegal(3L)).hasMessage("无权操作：仅法人可执行");
        assertThat(service.hasPermission(3L, "all")).isFalse();
        assertThat(service.hasPermission(3L, "contract_sign")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsMalformedAndUnauthorizedCompanySelection() {
        assertThatThrownBy(() -> service.resolveCompanyId("abc"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("企业 ID 格式不正确");

        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThatThrownBy(() -> service.resolveCompanyId("9"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("你不是该企业的有效成员");
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinesBuiltInAndCustomPermissions() {
        CompanyMemberDO member = activeMember("FINANCE", "[\"order_create\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        assertThat(service.hasPermission(3L, "invoice_view")).isTrue();
        assertThat(service.hasPermission(3L, "order_create")).isTrue();
        assertThat(service.hasPermission(3L, "member_manage")).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistedRoleCanGrantAllPermissions() {
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeMember("CUSTOM", null));
        RoleDefDO role = new RoleDefDO();
        role.setPermissions("[\"all\"]");
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(role);

        assertThat(service.hasPermission(3L, "anything")).isTrue();
        assertThatCode(() -> service.requirePermission(3L, "anything")).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void deniesMissingMemberAndMalformedPermissionJson() {
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.hasPermission(3L, "order_view")).isFalse();
        assertThatThrownBy(() -> service.requireAnyPermission(3L, "a", "b"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权执行该操作");

        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeMember("FINANCE", "not-json"));
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThatThrownBy(() -> service.hasPermission(3L, "invoice_view"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("权限配置格式错误");
    }

    @Test
    @SuppressWarnings("unchecked")
    void enforcesLegalAndClaimBoundaries() {
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L, 1L, 0L, 1L);
        assertThatThrownBy(() -> service.requireLegal(3L)).hasMessage("无权操作：仅法人可执行");
        assertThatCode(() -> service.requireLegal(3L)).doesNotThrowAnyException();
        assertThatThrownBy(() -> service.requireLegalOrClaim(3L)).hasMessage("无权执行企业认领认证");
        assertThatCode(() -> service.requireLegalOrClaim(3L)).doesNotThrowAnyException();
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopesCompanyProfilesToMembersAndActiveCounterparties() {
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(1L, 0L, 1L, 0L, 0L, 1L);
        assertThat(service.requireCompanyProfileAccess(9L))
                .isEqualTo(AccessControlOperations.CompanyProfileAccess.SENSITIVE_OWNER);
        assertThat(service.requireCompanyProfileAccess(9L))
                .isEqualTo(AccessControlOperations.CompanyProfileAccess.MEMBER);

        when(relationMapper.countActiveBetween(3L, 9L)).thenReturn(1L);
        assertThat(service.requireCompanyProfileAccess(9L))
                .isEqualTo(AccessControlOperations.CompanyProfileAccess.COUNTERPARTY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsUnrelatedCompanyProfileAccess() {
        when(memberMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThatThrownBy(() -> service.requireCompanyProfileAccess(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessage("无权访问企业敏感资料");
    }

    @Test
    void editedDefaultRoleRemovesOldPermissionsAndAddsNewOnNextCheck() {
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(activeMember("ADMIN", null));
        RoleDefDO role = new RoleDefDO();
        role.setName("管理员");
        role.setPermissions("[\"member_manage\",\"order_view\"]");
        when(roleMapper.selectOne(any(Wrapper.class))).thenReturn(role);
        assertThat(service.hasPermission(3L, "member_manage")).isTrue();
        assertThat(service.hasPermission(3L, "order_view")).isTrue();
        assertThat(service.hasPermission(3L, "seal_manage")).isFalse();
        role.setPermissions("[]");
        assertThat(service.hasPermission(3L, "member_manage")).isFalse();
        assertThatThrownBy(() -> service.requireManager(3L)).hasMessage("无权执行该操作");
    }

    @Test
    void unionsAssignedRolesAndRevokesOnlyPermissionsNoLongerGranted() {
        MybatisTestSupport.initialize(CompanyMemberDO.class, RoleDefDO.class);
        CompanyMemberDO member = activeMember("SALES", null);
        member.setRoleCodes("[\"SALES\",\"FINANCE\"]");
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(member);
        RoleDefDO sales = new RoleDefDO();
        sales.setName("销售员");
        sales.setPermissions("[\"order_create\",\"contract_view\"]");
        RoleDefDO finance = new RoleDefDO();
        finance.setName("财务");
        finance.setPermissions("[\"invoice_view\",\"contract_view\"]");
        when(roleMapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
            var query = (com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RoleDefDO>) invocation.getArgument(0);
            query.getSqlSegment();
            assertThat(query.getParamNameValuePairs().values()).contains(3L);
            return query.getParamNameValuePairs().containsValue("SALES") ? sales : finance;
        });
        var effective = service.effectiveRole(3L, 7L);
        assertThat(effective.name()).isEqualTo("销售员、财务");
        assertThat(effective.permissions()).containsExactly("contract_view", "invoice_view", "order_create");
        member.setRoleCode("FINANCE");
        member.setRoleCodes("[\"FINANCE\"]");
        assertThat(service.hasPermission(3L, "order_create")).isFalse();
        assertThat(service.hasPermission(3L, "contract_view")).isTrue();
        finance.setPermissions("[]");
        assertThat(service.hasPermission(3L, "invoice_view")).isFalse();
        when(memberMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        assertThat(service.effectiveRole(3L, 7L).permissions()).isEmpty();
    }

    private CompanyMemberDO activeMember(String role, String customPermissions) {
        CompanyMemberDO member = new CompanyMemberDO();
        member.setRoleCode(role);
        member.setStatus("ACTIVE");
        member.setCustomPermissions(customPermissions);
        return member;
    }
}
