package com.tradepass.module.identity.service.company;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import com.tradepass.module.identity.service.notice.MemberRemovalNoticeService;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionService;

import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.AuthorizationRecord;
import com.tradepass.framework.common.pojo.TradePassDtos.MemberRole;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyProfile;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanySearchSummary;
import com.tradepass.framework.common.pojo.TradePassDtos.SealRecord;
import com.tradepass.module.identity.controller.app.company.vo.ApproveReqVO;
import com.tradepass.module.identity.controller.app.company.vo.CompanySubmitReqVO;
import com.tradepass.module.identity.controller.app.company.vo.InviteReqVO;
import com.tradepass.module.identity.controller.app.company.vo.JoinReqVO;
import com.tradepass.module.identity.controller.app.company.vo.RoleReqVO;
import com.tradepass.module.identity.controller.app.company.vo.SealReqVO;
import com.tradepass.module.identity.controller.app.company.vo.VerificationReqVO;
import com.tradepass.module.identity.api.company.dto.InviteResult;
import com.tradepass.module.identity.api.company.dto.JoinResult;
import com.tradepass.framework.common.pojo.PagePayload;
import com.tradepass.module.identity.api.permission.dto.RoleRespDTO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.api.fadada.dto.PersonalIdentityRespDTO;
import com.tradepass.module.identity.dal.dataobject.counterparty.CounterpartyRelationEntityDO;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyInviteMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.counterparty.CounterpartyRelationMapper;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.mysql.permission.RoleDefMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class CompanyServiceImpl implements CompanyService {
    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper companyMemberMapper;
    private final CompanyInviteMapper companyInviteMapper;
    private final CounterpartyRelationMapper counterpartyRelationMapper;
    private final RoleDefMapper roleDefMapper;
    private final PermDefMapper permDefMapper;
    private final AccessControlService accessControlService;
    private final CompanySearchRateLimiter companySearchRateLimiter;
    private final RolePermissionService rolePermissionService;
    private final AuditLogService auditLogService;
    private final MemberRemovalNoticeService memberRemovalNoticeService;
    private final boolean caMockEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private FadadaPersonalIdentityService personalIdentityService;

    public CompanyServiceImpl(CompanyMapper companyMapper,
                          CompanyMemberMapper companyMemberMapper,
                          CompanyInviteMapper companyInviteMapper,
                          CounterpartyRelationMapper counterpartyRelationMapper,
                          RoleDefMapper roleDefMapper,
                          PermDefMapper permDefMapper,
                          AccessControlService accessControlService,
                          CompanySearchRateLimiter companySearchRateLimiter,
                          RolePermissionService rolePermissionService,
                          AuditLogService auditLogService,
                          MemberRemovalNoticeService memberRemovalNoticeService,
                          @Value("${tradepass.ca.mock-enabled:false}") boolean caMockEnabled) {
        this.companyMapper = companyMapper;
        this.companyMemberMapper = companyMemberMapper;
        this.companyInviteMapper = companyInviteMapper;
        this.counterpartyRelationMapper = counterpartyRelationMapper;
        this.roleDefMapper = roleDefMapper;
        this.permDefMapper = permDefMapper;
        this.accessControlService = accessControlService;
        this.companySearchRateLimiter = companySearchRateLimiter;
        this.rolePermissionService = rolePermissionService;
        this.auditLogService = auditLogService;
        this.memberRemovalNoticeService = memberRemovalNoticeService;
        this.caMockEnabled = caMockEnabled;
    }

    @Autowired
    public void setPersonalIdentityService(FadadaPersonalIdentityService personalIdentityService) {
        this.personalIdentityService = personalIdentityService;
    }

    public List<CompanySearchSummary> searchCompanies(String keyword) {
        companySearchRateLimiter.check(AuthContext.userId());
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return List.of();
        }
        if (kw.length() < 2) {
            throw new BusinessException("搜索关键词至少需要 2 个字符");
        }
        if (kw.length() > 50 || kw.contains("%") || kw.contains("_")) {
            throw new BusinessException("搜索关键词格式不正确");
        }
        return companyMapper.selectList(new LambdaQueryWrapper<CompanyDO>()
                        .and(query -> query.like(CompanyDO::getName, kw).or().like(CompanyDO::getCreditCode, kw))
                        .orderByAsc(CompanyDO::getName)
                        .last("LIMIT 20"))
                .stream().map(this::toCompanySearchSummary).toList();
    }

    public CompanyProfile getCompany(String id) {
        long companyId = parseId(id);
        CompanyDO company = companyMapper.selectById(companyId);
        if (company == null) {
            throw new BusinessException("企业不存在");
        }
        AccessControlOperations.CompanyProfileAccess access = accessControlService.requireCompanyProfileAccess(companyId);
        return access == AccessControlOperations.CompanyProfileAccess.SENSITIVE_OWNER
                ? toCompanyProfile(company)
                : toRestrictedCompanyProfile(company);
    }

    /** Includes unfinished submissions even if the subsequent claim request was interrupted. */
    public List<CompanyProfile> myOnboardingCompanies() {
        return companyMapper.selectList(new LambdaQueryWrapper<CompanyDO>()
                        .eq(CompanyDO::getCreatedBy, AuthContext.userId())
                        .in(CompanyDO::getCertificationStatus, List.of("PENDING", "PENDING_REVIEW", "REJECTED"))
                        .orderByDesc(CompanyDO::getId))
                .stream().map(this::toCompanyProfile).toList();
    }

    @Transactional
    public CompanyProfile submitCompany(CompanySubmitReqVO request) {
        CompanyDO company = companyMapper.selectOne(new LambdaQueryWrapper<CompanyDO>().eq(CompanyDO::getCreditCode, request.creditCode()).last("LIMIT 1 FOR UPDATE"));
        if (company == null) {
            if (personalIdentityService != null) {
                PersonalIdentityRespDTO identity = personalIdentityService.requireCurrentVerified();
                if (!"VERIFIED".equals(identity.status())) {
                    throw new BusinessException("请先完成个人实名认证，再创建企业");
                }
            }
            company = new CompanyDO();
            company.setCreditCode(request.creditCode());
            company.setCreatedBy(AuthContext.userId());
            company.setCertificationStatus("PENDING");
            company.setRealNameStatus("NOT_STARTED");
            company.setFaceStatus("NOT_STARTED");
            company.setSealStatus("NOT_UPLOADED");
        } else if (!"VERIFIED".equals(company.getCertificationStatus())
                && (company.getCreatedBy() == null || company.getCreatedBy() != AuthContext.userId())) {
            throw new BusinessException("企业已入驻，请通过企业邀请或认领流程加入");
        }
        if (company.getId() != null && "VERIFIED".equals(company.getCertificationStatus())) {
            accessControlService.requireLegal(company.getId());
        }
        boolean identityLocked = "VERIFIED".equals(company.getCertificationStatus())
                || "PENDING_REVIEW".equals(company.getCertificationStatus());
        if (identityLocked && (!java.util.Objects.equals(trim(company.getName()), trim(request.name()))
                || !java.util.Objects.equals(trim(company.getLegalPersonName()), trim(request.legalPersonName())))) {
            throw new BusinessException("认证中或已认证的企业名称及法人信息不能直接修改，请先处理企业认证变更");
        }
        if (!identityLocked) {
            company.setName(request.name());
            company.setLegalPersonName(request.legalPersonName());
        }
        company.setRegisteredAddress(trim(request.registeredAddress()));
        company.setContactPhone(trim(request.contactPhone()));
        company.setBankName(trim(request.bankName()));
        company.setBankAccount(trim(request.bankAccount()));
        if (companyMapper.selectById(company.getId()) == null) {
            companyMapper.insert(company);
        } else {
            companyMapper.updateById(company);
        }
        return toCompanyProfile(companyMapper.selectById(company.getId()));
    }

    public CompanyProfile submitCertification(String id) {
        accessControlService.requireLegalOrClaim(parseId(id));
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, parseId(id))
                .set(CompanyDO::getCertificationStatus, "PENDING_REVIEW"));
        return getCompany(id);
    }

    public CompanyProfile verifyRealName(VerificationReqVO req) {
        requireVerificationProvider();
        accessControlService.requireLegalOrClaim(parseId(req.companyId()));
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, parseId(req.companyId()))
                .set(CompanyDO::getRealNameStatus, "VERIFIED"));
        return getCompany(req.companyId());
    }

    public CompanyProfile verifyFace(VerificationReqVO req) {
        requireVerificationProvider();
        accessControlService.requireLegalOrClaim(parseId(req.companyId()));
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, parseId(req.companyId()))
                .set(CompanyDO::getFaceStatus, "VERIFIED"));
        return getCompany(req.companyId());
    }

    public SealRecord uploadSeal(SealReqVO req) {
        requireVerificationProvider();
        accessControlService.requireLegalOrClaim(parseId(req.companyId()));
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, parseId(req.companyId()))
                .set(CompanyDO::getSealStatus, "UPLOADED"));
        return new SealRecord("MOCK-SEAL-" + UUID.randomUUID(), req.companyId(), req.fileUrl(), req.usage(), "UPLOADED");
    }

    public boolean caMockEnabled() {
        return caMockEnabled;
    }

    public InviteResult createInvite(InviteReqVO req) {
        long companyId = parseId(req.companyId());
        accessControlService.requireManager(companyId);
        String code = generateInviteCode();
        saveInvite(companyId, code, "member", null);
        return new InviteResult(code, req.companyId());
    }

    public InviteResult createCounterpartyInvite(InviteReqVO req) {
        long companyId = parseId(req.companyId());
        accessControlService.requireLegal(companyId);
        String code = generateInviteCode();
        String relationRole = "supplier".equalsIgnoreCase(req.relationRole()) ? "supplier" : "buyer";
        saveInvite(companyId, code, "counterparty", relationRole);
        return new InviteResult(code, req.companyId());
    }

    @Transactional
    public JoinResult joinCompany(JoinReqVO req) {
        CompanyInviteDO invite = companyInviteMapper.selectOne(new LambdaQueryWrapper<CompanyInviteDO>()
                .eq(CompanyInviteDO::getCode, req.code())
                .last("LIMIT 1 FOR UPDATE"));
        if (invite == null) {
            throw new BusinessException("邀请码无效");
        }
        if (Boolean.TRUE.equals(invite.getUsed())) {
            throw new BusinessException("邀请码已被使用");
        }
        if (invite.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new BusinessException("邀请码已过期");
        }

        long userId = AuthContext.userId();
        if ("counterparty".equals(invite.getType())) {
            long currentCompanyId = AuthContext.requireCompanyId();
            if (Long.valueOf(currentCompanyId).equals(invite.getCompanyId())) {
                throw new BusinessException("不能接受本企业发出的合作邀请");
            }
            CompanyMemberDO legalMember = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                    .eq(CompanyMemberDO::getUserId, userId)
                    .eq(CompanyMemberDO::getCompanyId, currentCompanyId)
                    .eq(CompanyMemberDO::getRoleCode, "LEGAL")
                    .eq(CompanyMemberDO::getStatus, "ACTIVE")
                    .last("LIMIT 1"));
            if (legalMember == null) {
                throw new BusinessException("仅公司法人可接受企业合作邀请，请先在您的公司完成法人认证");
            }
            CompanyDO userCompany = companyMapper.selectById(legalMember.getCompanyId());
            CompanyDO inviterCompany = companyMapper.selectById(invite.getCompanyId());
            String companyName = userCompany == null ? "未知企业" : userCompany.getName();
            CounterpartyRelationEntityDO relation = new CounterpartyRelationEntityDO();
            if ("supplier".equalsIgnoreCase(invite.getRelationRole())) {
                relation.setCompanyId(legalMember.getCompanyId());
                relation.setCounterpartyCompanyId(invite.getCompanyId());
                relation.setCounterpartyCompanyName(inviterCompany == null ? "未知企业" : inviterCompany.getName());
            } else {
                relation.setCompanyId(invite.getCompanyId());
                relation.setCounterpartyCompanyId(legalMember.getCompanyId());
                relation.setCounterpartyCompanyName(companyName);
            }
            relation.setRelationType("SUPPLIER");
            relation.setStatus("ACTIVE");
            counterpartyRelationMapper.insert(relation);
            markInviteUsed(invite, userId);
            String relationText = "supplier".equalsIgnoreCase(invite.getRelationRole()) ? "客户关系" : "供应商关系";
            return new JoinResult("ACTIVE", "已建立" + relationText);
        }

        CompanyMemberDO existing = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, invite.getCompanyId())
                .eq(CompanyMemberDO::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null) {
            if ("ACTIVE".equals(existing.getStatus())) {
                throw new BusinessException("你已是该企业成员");
            }
            if ("PENDING".equals(existing.getStatus())) {
                throw new BusinessException("申请已提交，等待审批");
            }
        }

        CompanyMemberDO member = new CompanyMemberDO();
        member.setCompanyId(invite.getCompanyId());
        member.setUserId(userId);
        member.setRoleCode("GUEST");
        member.setIsLegalPerson(false);
        member.setIsAdministrator(false);
        member.setStatus("PENDING");
        companyMemberMapper.insert(member);
        markInviteUsed(invite, userId);
        return new JoinResult("PENDING", "申请已提交，等待管理员审批");
    }

    public List<AuthorizationRecord> listMembers(String companyId) {
        return companyMemberMapper.selectAuthorizationRecords(parseId(companyId)).stream()
                .map(row -> toAuthorizationRecord(row, companyId))
                .toList();
    }

    public PagePayload<AuthorizationRecord> pageMembers(String companyId, String status, int page, int size) {
        long cid = accessControlService.resolveCompanyId(companyId);
        accessControlService.requireManager(cid);
        int normalizedPage = Math.max(1, page);
        int normalizedSize = Math.max(1, Math.min(size, 100));
        String cleanStatus = status == null ? null : status.trim();
        LambdaQueryWrapper<CompanyMemberDO> countQuery = new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, cid);
        if (cleanStatus != null && !cleanStatus.isBlank()) {
            countQuery.eq(CompanyMemberDO::getStatus, cleanStatus);
        }
        long total = companyMemberMapper.selectCount(countQuery);
        long offset = (long) (normalizedPage - 1) * normalizedSize;
        List<AuthorizationRecord> items = companyMemberMapper.selectAuthorizationPageRecords(
                        cid, cleanStatus, normalizedSize, offset).stream()
                .map(row -> toAuthorizationRecord(row, String.valueOf(cid)))
                .toList();
        return PagePayload.of(items, total, normalizedPage, normalizedSize);
    }

    public AuthorizationRecord approveMember(String id, ApproveReqVO req, String companyId) {
        long cid = parseId(companyId);
        accessControlService.requireManager(cid);
        long memberId = parseId(id);
        CompanyMemberDO target = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, memberId)
                .eq(CompanyMemberDO::getCompanyId, cid)
                .eq(CompanyMemberDO::getStatus, "PENDING")
                .last("LIMIT 1"));
        if (target == null) {
            throw new BusinessException("成员申请不存在或状态已变化");
        }
        List<RoleDefDO> roles = requireAssignableRoles(cid, req);
        RoleDefDO role = roles.get(0);
        List<String> roleCodes = roles.stream().map(RoleDefDO::getCode).toList();
        List<String> rolePermissions = roles.stream().flatMap(item -> parsePermissions(item.getPermissions()).stream())
                .distinct().toList();
        List<String> customPermissions = req.customPermissions() == null ? List.of() : req.customPermissions();
        validateGrantablePermissions(cid, rolePermissions);
        if (!customPermissions.isEmpty()) {
            validateGrantablePermissions(cid, customPermissions);
        }
        String perms = !customPermissions.isEmpty()
                ? toJson(customPermissions)
                : null;
        int updated = companyMemberMapper.update(new LambdaUpdateWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, memberId)
                .eq(CompanyMemberDO::getCompanyId, cid)
                .eq(CompanyMemberDO::getStatus, "PENDING")
                .set(CompanyMemberDO::getRoleCode, role.getCode())
                .set(CompanyMemberDO::getRoleCodes, toJson(roleCodes))
                .set(CompanyMemberDO::getCustomPermissions, perms)
                .set(CompanyMemberDO::getIsLegalPerson, false)
                .set(CompanyMemberDO::getIsAdministrator, "ADMIN".equals(role.getCode()))
                .set(CompanyMemberDO::getStatus, "ACTIVE"));
        if (updated != 1) {
            throw new BusinessException("成员申请状态已变化，请刷新后重试");
        }
        Set<String> effective = new LinkedHashSet<>(rolePermissions);
        effective.addAll(customPermissions);
        auditLogService.log(cid, "COMPANY_MEMBER", memberId, "APPROVE", "分配角色 " + String.join("、", roleCodes));
        return new AuthorizationRecord(id, companyId, String.valueOf(target.getUserId()), "", role.getCode(),
                String.join("、", memberRoles(roles).stream().map(MemberRole::name).toList()),
                List.copyOf(effective), "ACTIVE", "", null, memberRoles(roles));
    }

    public void rejectMember(String id, String companyId) {
        long cid = parseId(companyId);
        accessControlService.requireManager(cid);
        int deleted = companyMemberMapper.delete(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, parseId(id))
                .eq(CompanyMemberDO::getCompanyId, cid)
                .eq(CompanyMemberDO::getStatus, "PENDING"));
        if (deleted != 1) {
            throw new BusinessException("成员申请不存在或状态已变化");
        }
        auditLogService.log(cid, "COMPANY_MEMBER", id, "REJECT", "拒绝成员加入申请");
    }

    public void updateMemberRole(String id, ApproveReqVO req, String companyId) {
        long cid = parseId(companyId);
        accessControlService.requireManager(cid);
        long memberId = parseId(id);
        CompanyMemberDO target = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, memberId)
                .eq(CompanyMemberDO::getCompanyId, cid)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (target == null) throw new BusinessException("成员不存在或状态已变化");
        if (Boolean.TRUE.equals(target.getIsLegalPerson()) || "LEGAL".equals(target.getRoleCode())) {
            throw new BusinessException("法人只能通过法人变更流程移交");
        }
        List<RoleDefDO> roles = requireAssignableRoles(cid, req);
        RoleDefDO role = roles.get(0);
        List<String> roleCodes = roles.stream().map(RoleDefDO::getCode).toList();
        for (RoleDefDO assignedRole : roles) {
            validateGrantablePermissions(cid, parsePermissions(assignedRole.getPermissions()));
        }
        if (req.customPermissions() != null && !req.customPermissions().isEmpty()) {
            throw new BusinessException("请通过角色管理配置权限后再分配");
        }
        int updated = companyMemberMapper.update(new LambdaUpdateWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, memberId)
                .eq(CompanyMemberDO::getCompanyId, cid)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")
                .eq(CompanyMemberDO::getRoleCode, target.getRoleCode())
                .apply("COALESCE(role_codes, JSON_ARRAY()) = CAST({0} AS JSON)",
                        target.getRoleCodes() == null ? "[]" : target.getRoleCodes())
                .and(q -> q.eq(CompanyMemberDO::getIsLegalPerson, false).or().isNull(CompanyMemberDO::getIsLegalPerson))
                .set(CompanyMemberDO::getRoleCode, role.getCode())
                .set(CompanyMemberDO::getRoleCodes, toJson(roleCodes))
                .set(CompanyMemberDO::getIsAdministrator, "ADMIN".equals(role.getCode()))
                .set(CompanyMemberDO::getCustomPermissions, null));
        if (updated != 1) throw new BusinessException("成员状态已变化，请刷新后重试");
        auditLogService.log(cid, "COMPANY_MEMBER", memberId, "UPDATE_ROLE", "调整角色 " + String.join("、", roleCodes));
    }

    @Transactional
    public void removeMember(String id, String companyId) {
        long cid = parseId(companyId);
        accessControlService.requireManager(cid);
        long memberId = parseId(id);
        CompanyMemberDO target = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, memberId)
                .eq(CompanyMemberDO::getCompanyId, cid)
                .last("LIMIT 1"));
        if (target == null) {
            throw new BusinessException("成员不存在");
        }
        if (target.getUserId() == AuthContext.userId()) {
            throw new BusinessException("不能移除当前登录成员");
        }
        if (Boolean.TRUE.equals(target.getIsLegalPerson()) || "LEGAL".equals(target.getRoleCode())) {
            throw new BusinessException("法人只能通过法人变更流程移交");
        }
        int deleted = companyMemberMapper.delete(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getId, memberId)
                .eq(CompanyMemberDO::getCompanyId, cid)
                .eq(CompanyMemberDO::getStatus, "ACTIVE"));
        if (deleted != 1) {
            throw new BusinessException("成员状态已变化，请刷新后重试");
        }
        memberRemovalNoticeService.recordRemoval(target.getUserId(), cid);
        auditLogService.log(cid, "COMPANY_MEMBER", memberId, "REMOVE", "移除企业成员");
    }

    public List<RoleRespDTO> listRoles(String companyId) {
        long cid = accessControlService.resolveCompanyId(companyId);
        accessControlService.requireManager(cid);
        return roleDefMapper.selectList(new LambdaQueryWrapper<RoleDefDO>()
                        .eq(RoleDefDO::getCompanyId, cid)
                        .orderByDesc(RoleDefDO::getSystemRole)
                        .orderByAsc(RoleDefDO::getId))
                .stream().map(this::toRolePayload).toList();
    }

    public RoleRespDTO createRole(RoleReqVO req) {
        long companyId = parseId(req.companyId());
        accessControlService.requireManager(companyId);
        validateGrantablePermissions(companyId, req.permissions());
        RoleDefDO role = new RoleDefDO();
        role.setCompanyId(companyId);
        role.setCode("CUSTOM_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase());
        role.setName(req.name());
        role.setSystemRole(false);
        role.setPermissions(toJson(req.permissions()));
        roleDefMapper.insert(role);
        auditLogService.log(companyId, "ROLE", role.getId(), "CREATE", "创建角色 " + role.getCode());
        return toRolePayload(role);
    }

    public void updateRole(String id, RoleReqVO req) {
        long companyId = parseId(req.companyId());
        accessControlService.requireManager(companyId);
        RoleDefDO existing = roleDefMapper.selectById(parseId(id));
        if (existing == null || existing.getCompanyId() != companyId) {
            throw new BusinessException("角色不存在");
        }
        if (isProtectedRole(existing)) {
            throw new BusinessException("法人及认证身份角色不可编辑");
        }
        validateGrantablePermissions(companyId, req.permissions());
        int updated = roleDefMapper.update(new LambdaUpdateWrapper<RoleDefDO>()
                .eq(RoleDefDO::getId, existing.getId())
                .eq(RoleDefDO::getCompanyId, companyId)
                .set(RoleDefDO::getName, Boolean.TRUE.equals(existing.getSystemRole()) ? existing.getName() : req.name().trim())
                .set(RoleDefDO::getPermissions, toJson(req.permissions())));
        if (updated != 1) {
            throw new BusinessException("角色状态已变化，请刷新后重试");
        }
        auditLogService.log(companyId, "ROLE", existing.getId(), "UPDATE", "更新角色 " + existing.getCode());
    }

    public void deleteRole(String id) {
        RoleDefDO role = roleDefMapper.selectById(parseId(id));
        if (role == null) {
            return;
        }
        accessControlService.requireManager(role.getCompanyId());
        if (Boolean.TRUE.equals(role.getSystemRole()) || isProtectedRole(role)) {
            throw new BusinessException("系统角色不可删除");
        }
        if (companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, role.getCompanyId())
                .and(q -> q.eq(CompanyMemberDO::getRoleCode, role.getCode())
                        .or().apply("JSON_CONTAINS(role_codes, JSON_QUOTE({0}))", role.getCode()))) > 0) {
            throw new BusinessException("角色仍有成员使用，请先迁移成员角色");
        }
        roleDefMapper.deleteById(role.getId());
        auditLogService.log(role.getCompanyId(), "ROLE", role.getId(), "DELETE", "删除角色 " + role.getCode());
    }

    private void saveInvite(long companyId, String code, String type, String relationRole) {
        CompanyInviteDO invite = new CompanyInviteDO();
        invite.setCompanyId(companyId);
        invite.setCode(code);
        invite.setType(type);
        invite.setRelationRole(relationRole);
        invite.setUsed(false);
        invite.setExpiresAt(LocalDateTime.now().plusHours(24));
        companyInviteMapper.insert(invite);
    }

    private void markInviteUsed(CompanyInviteDO invite, long userId) {
        invite.setUsed(true);
        invite.setUsedBy(userId);
        int updated = companyInviteMapper.update(new LambdaUpdateWrapper<CompanyInviteDO>()
                .eq(CompanyInviteDO::getId, invite.getId())
                .eq(CompanyInviteDO::getUsed, false)
                .gt(CompanyInviteDO::getExpiresAt, LocalDateTime.now())
                .set(CompanyInviteDO::getUsed, true)
                .set(CompanyInviteDO::getUsedBy, userId));
        if (updated != 1) throw new BusinessException("邀请码已被使用或已过期，请刷新后重试");
    }

    private CompanyProfile toCompanyProfile(CompanyDO company) {
        return new CompanyProfile(String.valueOf(company.getId()), company.getName(), company.getCreditCode(),
                company.getLegalPersonName(), company.getRegisteredAddress(), company.getContactPhone(),
                company.getBankName(), company.getBankAccount(), company.getCertificationStatus(), company.getRealNameStatus(),
                company.getFaceStatus(), company.getSealStatus());
    }

    private CompanySearchSummary toCompanySearchSummary(CompanyDO company) {
        return new CompanySearchSummary(String.valueOf(company.getId()), company.getName(),
                maskCreditCode(company.getCreditCode()), "VERIFIED".equals(company.getCertificationStatus()));
    }

    private CompanyProfile toRestrictedCompanyProfile(CompanyDO company) {
        return new CompanyProfile(String.valueOf(company.getId()), company.getName(), company.getCreditCode(),
                company.getLegalPersonName(), company.getRegisteredAddress(), maskPhone(company.getContactPhone()),
                company.getBankName(), maskBankAccount(company.getBankAccount()), company.getCertificationStatus(),
                null, null, company.getSealStatus());
    }

    private String maskCreditCode(String value) {
        return maskMiddle(value, 4, 4);
    }

    private String maskPhone(String value) {
        return maskMiddle(value, 3, 4);
    }

    private String maskBankAccount(String value) {
        return maskMiddle(value, 0, 4);
    }

    private String maskMiddle(String value, int visiblePrefix, int visibleSuffix) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int length = value.length();
        if (length <= visiblePrefix + visibleSuffix) {
            return "*".repeat(length);
        }
        return value.substring(0, visiblePrefix)
                + "*".repeat(length - visiblePrefix - visibleSuffix)
                + value.substring(length - visibleSuffix);
    }

    private AuthorizationRecord toAuthorizationRecord(Map<String, Object> row, String companyId) {
        String roleCode = string(row.get("roleCode"));
        String name = string(row.get("nickname"));
        String phone = string(row.get("phone"));
        String displayName = name != null && !name.isBlank() && !"新用户".equals(name)
                ? name
                : (phone != null && !phone.isBlank() ? "用户" + phone.substring(phone.length() - 4) : "微信用户");
        String verifiedName = string(row.get("verifiedName"));
        if (verifiedName != null && !verifiedName.isBlank()) {
            displayName = verifiedName.trim();
        }
        String status = string(row.get("status"));
        AccessControlOperations.EffectiveRole effective = "ACTIVE".equals(status)
                ? accessControlService.effectiveRole(parseId(companyId), parseId(string(row.get("userId"))))
                : new AccessControlOperations.EffectiveRole(roleCode, rolePermissionService.roleText(roleCode), List.of());
        return new AuthorizationRecord(String.valueOf(row.get("id")), companyId, String.valueOf(row.get("userId")),
                displayName, effective.code(), effective.name(), effective.permissions(), status,
                phone != null ? phone : "", "VERIFIED".equals(string(row.get("identityStatus"))), effective.roles());
    }

    private List<MemberRole> memberRoles(List<RoleDefDO> roles) {
        return roles.stream().map(role -> new MemberRole(role.getCode(),
                role.getName() == null ? rolePermissionService.roleText(role.getCode()) : role.getName())).toList();
    }

    private List<RoleDefDO> requireAssignableRoles(long companyId, ApproveReqVO req) {
        List<String> requested = req.roleCodes() == null
                ? (req.roleCode() == null ? List.of() : List.of(req.roleCode())) : req.roleCodes();
        if (requested.isEmpty()) throw new BusinessException("请至少选择一个角色");
        if (requested.size() > 30) throw new BusinessException("一次最多分配30个角色");
        List<RoleDefDO> roles = requested.stream().distinct()
                .map(code -> requireAssignableRole(companyId, code)).toList();
        // Keep existing clients' administrator and guest checks consistent with the assigned roles.
        return roles.stream().sorted(java.util.Comparator.comparingInt(role ->
                "ADMIN".equals(role.getCode()) ? 0 : "GUEST".equals(role.getCode()) ? 2 : 1)).toList();
    }

    private RoleDefDO requireAssignableRole(long companyId, String roleCode) {
        if (roleCode == null || roleCode.isBlank() || "LEGAL".equals(roleCode) || "LEGAL_CANDIDATE".equals(roleCode)) {
            throw new BusinessException("该角色不能通过成员审批分配");
        }
        RoleDefDO role = roleDefMapper.selectOne(new LambdaQueryWrapper<RoleDefDO>()
                .eq(RoleDefDO::getCompanyId, companyId)
                .eq(RoleDefDO::getCode, roleCode)
                .last("LIMIT 1"));
        if (role == null) {
            throw new BusinessException("角色不存在或不属于当前企业");
        }
        return role;
    }

    private void validateGrantablePermissions(long companyId, List<String> permissions) {
        if (permissions == null) {
            throw new BusinessException("请选择角色权限");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String permission : permissions) {
            String code = permission == null ? "" : permission.trim();
            if (code.isBlank() || "all".equals(code)) {
                throw new BusinessException("不能配置保留权限 " + code);
            }
            if (!unique.add(code)) {
                throw new BusinessException("权限配置存在重复项 " + code);
            }
            if (permDefMapper.selectById(code) == null) {
                throw new BusinessException("未知权限 " + code);
            }
            if (!accessControlService.hasPermission(companyId, code)) {
                throw new BusinessException("不能授予当前操作者不具备的权限 " + code);
            }
        }
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

    private RoleRespDTO toRolePayload(RoleDefDO role) {
        boolean systemRole = Boolean.TRUE.equals(role.getSystemRole());
        return new RoleRespDTO(String.valueOf(role.getId()), role.getCode(), role.getName(),
                parsePermissions(role.getPermissions()), systemRole, !isProtectedRole(role), !systemRole && !isProtectedRole(role));
    }

    private boolean isProtectedRole(RoleDefDO role) {
        return "LEGAL".equals(role.getCode()) || "LEGAL_CANDIDATE".equals(role.getCode());
    }

    private void requireVerificationProvider() {
        if (!caMockEnabled) {
            throw new BusinessException("认证服务尚未配置，无法完成该操作");
        }
    }

    private String toJson(List<String> permissions) {
        try {
            return objectMapper.writeValueAsString(permissions);
        } catch (Exception e) {
            throw new BusinessException("权限配置格式错误");
        }
    }

    private String generateInviteCode() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        var sb = new StringBuilder();
        var rng = new java.security.SecureRandom();
        for (int i = 0; i < 8; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }
}
