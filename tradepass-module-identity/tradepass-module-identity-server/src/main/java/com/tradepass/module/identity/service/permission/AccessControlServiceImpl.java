package com.tradepass.module.identity.service.permission;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.MemberRole;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

@Service
public class AccessControlServiceImpl implements AccessControlService {
    
    

    private final CompanyMemberMapper companyMemberMapper;
    private final CounterpartyRelationMapper counterpartyRelationMapper;
    private final RoleDefMapper roleDefMapper;
    private final RolePermissionService rolePermissionService;
    private final ObjectMapper objectMapper;

    public AccessControlServiceImpl(CompanyMemberMapper companyMemberMapper,
                                CounterpartyRelationMapper counterpartyRelationMapper,
                                RoleDefMapper roleDefMapper,
                                RolePermissionService rolePermissionService,
                                ObjectMapper objectMapper) {
        this.companyMemberMapper = companyMemberMapper;
        this.counterpartyRelationMapper = counterpartyRelationMapper;
        this.roleDefMapper = roleDefMapper;
        this.rolePermissionService = rolePermissionService;
        this.objectMapper = objectMapper;
    }

    public boolean isActiveMember(long companyId, long userId) {
        return companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")) > 0;
    }

    public Long resolveCompanyId(String companyId) {
        long contextCompanyId = AuthContext.requireCompanyId();
        if (companyId == null || companyId.isBlank()) {
            return contextCompanyId;
        }
        try {
            long requested = Long.parseLong(companyId);
            if (!isActiveMember(requested, AuthContext.userId())) {
                throw new BusinessException("你不是该企业的有效成员");
            }
            return requested;
        } catch (NumberFormatException e) {
            throw new BusinessException("企业 ID 格式不正确");
        }
    }

    public void requireManager(long companyId) {
        requireAnyPermission(companyId, "member_manage", "auth_manage");
    }

    public void requireLegal(long companyId) {
        boolean allowed = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, AuthContext.userId())
                .eq(CompanyMemberDO::getStatus, "ACTIVE")
                .eq(CompanyMemberDO::getRoleCode, "LEGAL")) > 0;
        if (!allowed) {
            throw new BusinessException("无权操作：仅法人可执行");
        }
    }

    public void requireMemberOrClaim(long companyId) {
        boolean allowed = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, AuthContext.userId())
                .in(CompanyMemberDO::getStatus, List.of("ACTIVE", "PENDING"))) > 0;
        if (!allowed) {
            throw new BusinessException("无权访问该企业");
        }
    }

    /**
     * 企业敏感资料仅对法人/候选法人开放；普通成员和有效合作方只能获得脱敏资料。
     */
    public CompanyProfileAccess requireCompanyProfileAccess(long targetCompanyId) {
        long userId = AuthContext.userId();
        boolean sensitiveOwner = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, targetCompanyId)
                .eq(CompanyMemberDO::getUserId, userId)
                .and(q -> q.eq(CompanyMemberDO::getRoleCode, "LEGAL")
                        .eq(CompanyMemberDO::getStatus, "ACTIVE")
                        .or(claim -> claim.eq(CompanyMemberDO::getRoleCode, "LEGAL_CANDIDATE")
                                .eq(CompanyMemberDO::getStatus, "PENDING")))) > 0;
        if (sensitiveOwner) {
            return CompanyProfileAccess.SENSITIVE_OWNER;
        }

        if (isActiveMember(targetCompanyId, userId)) {
            return CompanyProfileAccess.MEMBER;
        }

        Long currentCompanyId = AuthContext.companyId();
        if (currentCompanyId != null
                && isActiveMember(currentCompanyId, userId)
                && counterpartyRelationMapper.countActiveBetween(currentCompanyId, targetCompanyId) > 0) {
            return CompanyProfileAccess.COUNTERPARTY;
        }
        throw new BusinessException("无权访问企业敏感资料");
    }

    public void requireLegalOrClaim(long companyId) {
        boolean allowed = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, AuthContext.userId())
                .and(q -> q.eq(CompanyMemberDO::getRoleCode, "LEGAL")
                        .eq(CompanyMemberDO::getStatus, "ACTIVE")
                        .or(nested -> nested.eq(CompanyMemberDO::getRoleCode, "LEGAL_CANDIDATE")
                                .eq(CompanyMemberDO::getStatus, "PENDING")))) > 0;
        if (!allowed) {
            throw new BusinessException("无权执行企业认领认证");
        }
    }

    public void requireCertificationOperator(long companyId) {
        if (hasPermission(companyId, "company_manage")) return;
        requireLegalOrClaim(companyId);
    }

    public void requirePermission(long companyId, String permission) {
        if (!hasPermission(companyId, permission)) {
            throw new BusinessException("无权操作：缺少权限 " + permission);
        }
    }

    public void requireAnyPermission(long companyId, String... permissions) {
        for (String permission : permissions) {
            if (hasPermission(companyId, permission)) {
                return;
            }
        }
        throw new BusinessException("无权执行该操作");
    }

    public boolean hasPermission(long companyId, String permission) {
        return effectiveRole(companyId, AuthContext.userId()).permissions().stream()
                .anyMatch(value -> "all".equals(value) || permission.equals(value));
    }

    public EffectiveRole effectiveRole(long companyId, long userId) {
        CompanyMemberDO member = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (member == null) {
            RolePermissionService.RoleDef guest = rolePermissionService.role("GUEST");
            return new EffectiveRole("GUEST", guest.text(), guest.permissions());
        }

        Set<String> effective = new HashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        codes.add(member.getRoleCode());
        codes.addAll(parsePermissions(member.getRoleCodes()));
        List<MemberRole> assignedRoles = new ArrayList<>();
        for (String code : codes) {
            RoleDefDO role = roleDefMapper.selectOne(new LambdaQueryWrapper<RoleDefDO>()
                    .eq(RoleDefDO::getCompanyId, companyId)
                    .eq(RoleDefDO::getCode, code)
                    .last("LIMIT 1"));
            effective.addAll(role == null ? rolePermissionService.role(code).permissions()
                    : parsePermissions(role.getPermissions()));
            assignedRoles.add(new MemberRole(code, role == null || role.getName() == null
                    ? rolePermissionService.roleText(code) : role.getName()));
        }
        effective.addAll(parsePermissions(member.getCustomPermissions()));
        List<String> permissions = new ArrayList<>(effective);
        Collections.sort(permissions);
        String name = String.join("、", assignedRoles.stream().map(MemberRole::name).toList());
        return new EffectiveRole(member.getRoleCode(), name, List.copyOf(permissions), List.copyOf(assignedRoles));
    }

    private List<String> parsePermissions(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (Exception e) {
            throw new BusinessException("权限配置格式错误");
        }
    }
}
