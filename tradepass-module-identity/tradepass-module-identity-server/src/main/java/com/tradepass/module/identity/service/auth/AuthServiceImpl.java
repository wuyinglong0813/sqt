package com.tradepass.module.identity.service.auth;

import com.tradepass.module.identity.api.permission.AccessControlOperations;
import com.tradepass.module.identity.api.permission.AccessControlOperations.*;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.module.identity.dal.dataobject.permission.PermDefDO;
import com.tradepass.module.identity.service.permission.AccessControlService;
import com.tradepass.module.identity.service.permission.RolePermissionService;

import com.tradepass.module.trade.api.document.BusinessDocumentOperations;
import com.tradepass.module.trade.api.document.BusinessDocumentOperations.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyProfile;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyRole;
import com.tradepass.framework.common.pojo.TradePassDtos.DevUser;
import com.tradepass.framework.common.pojo.TradePassDtos.LoginSession;
import com.tradepass.framework.common.pojo.TradePassDtos.MePayload;
import com.tradepass.framework.common.pojo.TradePassDtos.MemberInfo;
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
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.trade.api.document.DocumentTodoReader;
import com.tradepass.module.trade.api.document.DocumentTodoReader.*;
import com.tradepass.module.identity.dal.mysql.permission.PermDefMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import com.tradepass.module.contract.api.contract.ContractReader;
import com.tradepass.module.contract.api.contract.ContractReader.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class AuthServiceImpl implements AuthService {
    private final SysUserMapper sysUserMapper;
    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper companyMemberMapper;
    private final PermDefMapper permDefMapper;
    private final ContractReader tradeContractMapper;
    private final DocumentTodoReader businessDocumentMapper;
    private final WechatService wechatService;
    private final RolePermissionService rolePermissionService;
    private final AccessControlService accessControlService;
    private final AuthSessionService authSessionService;
    private final ExperienceTestAccountService experienceTestAccountService;
    private final boolean devEnabled;

    public AuthServiceImpl(SysUserMapper sysUserMapper,
                       CompanyMapper companyMapper,
                       CompanyMemberMapper companyMemberMapper,
                       PermDefMapper permDefMapper,
                       ContractReader tradeContractMapper,
                       DocumentTodoReader businessDocumentMapper,
                       WechatService wechatService,
                       RolePermissionService rolePermissionService,
                       AccessControlService accessControlService,
                       AuthSessionService authSessionService,
                       ExperienceTestAccountService experienceTestAccountService,
                       @Value("${tradepass.dev.enabled:false}") boolean devEnabled) {
        this.sysUserMapper = sysUserMapper;
        this.companyMapper = companyMapper;
        this.companyMemberMapper = companyMemberMapper;
        this.permDefMapper = permDefMapper;
        this.tradeContractMapper = tradeContractMapper;
        this.businessDocumentMapper = businessDocumentMapper;
        this.wechatService = wechatService;
        this.rolePermissionService = rolePermissionService;
        this.accessControlService = accessControlService;
        this.authSessionService = authSessionService;
        this.experienceTestAccountService = experienceTestAccountService;
        this.devEnabled = devEnabled;
    }

    @Transactional
    public LoginSession wechatLogin(WechatLoginReqVO request) {
        return wechatLogin(request, null);
    }

    @Transactional
    public LoginSession wechatLogin(WechatLoginReqVO request, String trustedOpenid) {
        String openid = wechatService.resolveOpenid(request.code(), trustedOpenid);
        if (!devEnabled && request.phone() != null && !request.phone().isBlank()) {
            throw new BusinessException("生产环境不接受未经验证的手机号");
        }
        String phone = request.phoneCode() != null && !request.phoneCode().isBlank()
                ? wechatService.resolvePhoneByCode(request.phoneCode())
                : (devEnabled ? request.phone() : null);

        SysUserDO user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                .eq(SysUserDO::getOpenid, openid)
                .last("LIMIT 1"));
        if (user == null && phone != null && !phone.isBlank()) {
            user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                    .eq(SysUserDO::getPhone, phone)
                    .orderByAsc(SysUserDO::getId)
                    .last("LIMIT 1"));
        }
        if (user == null) {
            user = new SysUserDO();
            user.setOpenid(openid);
            user.setNickname(request.nickName() != null && !request.nickName().isBlank() ? request.nickName() : "微信用户");
            user.setPhone(phone != null ? phone : "");
            user.setStatus("ACTIVE");
            sysUserMapper.insert(user);
        } else {
            if (request.nickName() != null && !request.nickName().isBlank()) {
                user.setNickname(request.nickName());
            }
            if (phone != null && !phone.isBlank()) {
                user.setPhone(phone);
            }
            sysUserMapper.updateById(user);
        }

        Long experienceCompanyId = experienceTestAccountService.provisionIfConfigured(user, phone);

        List<CompanyRole> companies = loadUserCompanies(user.getId());
        String currentCompanyId = experienceCompanyId != null
                ? String.valueOf(experienceCompanyId)
                : (companies.isEmpty() ? null : companies.get(0).companyId());
        MemberInfo member = experienceCompanyId != null
                ? loadMember(user.getId(), experienceCompanyId)
                : loadMemberAnyCompany(user.getId());
        String roleCode = member == null || member.roleCode() == null ? "GUEST" : member.roleCode();
        return new LoginSession(tokenFor(user.getId()), new UserProfile(String.valueOf(user.getId()), openid,
                user.getPhone(), user.getNickname(), currentCompanyId, roleCode));
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public UserProfile bindPhone(BindPhoneReqVO request) {
        if (!devEnabled && request.phone() != null && !request.phone().isBlank()) {
            throw new BusinessException("生产环境不接受未经验证的手机号");
        }
        String phone = request.phoneCode() != null && !request.phoneCode().isBlank()
                ? wechatService.resolvePhoneByCode(request.phoneCode())
                : (devEnabled ? request.phone() : null);
        if (phone == null || phone.isBlank()) {
            throw new BusinessException("请使用微信手机号验证完成绑定");
        }
        long userId = AuthContext.userId();
        Long companyId = AuthContext.companyId();
        SysUserDO user = sysUserMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BusinessException("当前账号不可用，请重新登录");
        }
        SysUserDO other = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUserDO>()
                .eq(SysUserDO::getPhone, phone).ne(SysUserDO::getId, userId).last("LIMIT 1 FOR UPDATE"));
        if (other != null) {
            throw new BusinessException("该手机号已绑定其他账号，请使用原账号登录或联系管理员处理");
        }
        if (user.getPhone() != null && !user.getPhone().isBlank() && !phone.equals(user.getPhone())) {
            throw new BusinessException("当前账号已绑定手机号，变更手机号请联系管理员处理");
        }
        int updated = sysUserMapper.update(new LambdaUpdateWrapper<SysUserDO>().eq(SysUserDO::getId, userId)
                .and(wrapper -> wrapper.isNull(SysUserDO::getPhone).or().eq(SysUserDO::getPhone, "")
                        .or().eq(SysUserDO::getPhone, phone))
                .set(SysUserDO::getPhone, phone));
        if (updated != 1) throw new BusinessException("账号手机号状态已变化，请刷新后重试");
        MemberInfo member = loadMember(userId, companyId == null ? 0L : companyId);
        return new UserProfile(String.valueOf(userId), user.getOpenid(), phone, user.getNickname(),
                member == null || companyId == null ? null : String.valueOf(companyId),
                member == null ? "GUEST" : member.roleCode());
    }

    public MePayload me() {
        return buildMe(AuthContext.userId(), AuthContext.companyId());
    }

    public List<Map<String, Object>> permissions() {
        return permDefMapper.selectMaps(new LambdaQueryWrapper<com.tradepass.module.identity.dal.dataobject.permission.PermDefDO>()
                .select(com.tradepass.module.identity.dal.dataobject.permission.PermDefDO::getCode, com.tradepass.module.identity.dal.dataobject.permission.PermDefDO::getLabel)
                .orderByAsc(com.tradepass.module.identity.dal.dataobject.permission.PermDefDO::getSortOrder));
    }

    public List<TodoItem> myTodos() {
        long userId = AuthContext.userId();
        Long companyId = AuthContext.companyId();
        List<TodoItem> todos = new ArrayList<>();
        if (companyId == null) {
            return todos;
        }

        boolean manager = accessControlService.hasPermission(companyId, "member_manage")
                || accessControlService.hasPermission(companyId, "auth_manage");
        if (manager) {
            Long pending = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                    .eq(CompanyMemberDO::getCompanyId, companyId)
                    .eq(CompanyMemberDO::getStatus, "PENDING"));
            if (pending > 0) {
                todos.add(new TodoItem("APPROVAL", "成员待审批", pending + " 位同事申请加入企业", pending.intValue(), "auth-manage"));
            }

            CompanyDO company = companyMapper.selectById(companyId);
            if (company != null && company.getCertificationStatus() != null && !"VERIFIED".equals(company.getCertificationStatus())) {
                todos.add(new TodoItem("CERT", "企业认证待完成", "完成认证后可使用全部签约能力", 1, "company-cert"));
            }
        }

        if (accessControlService.hasPermission(companyId, "contract_sign")) {
            long pendingContracts = tradeContractMapper.countContractsAwaitingSignature(companyId);
            if (pendingContracts > 0) {
                todos.add(new TodoItem("CONTRACT", "合同待签署", pendingContracts + " 份合同等待你方签署",
                        (int) Math.min(Integer.MAX_VALUE, pendingContracts), "contract-approval"));
            }
        }

        if (accessControlService.hasPermission(companyId, "sales_order_receive")) {
            Long pendingSalesOrders = businessDocumentMapper.pendingDocumentCount(companyId);
            if (pendingSalesOrders > 0) {
                BusinessDocumentRespDTO latest = businessDocumentMapper.latestPendingDocument(companyId);
                String target = latest == null ? "" : "sales-order-detail:" + latest.getId();
                todos.add(new TodoItem("SALES_ORDER", "交易单据待确认",
                        pendingSalesOrders + " 份销售/退货单等待你方确认",
                        pendingSalesOrders.intValue(), target));
            }
        }
        return todos;
    }

    public MePayload switchCompany(SwitchCompanyReqVO request) {
        long userId = AuthContext.userId();
        long newCompanyId = parseId(request.companyId());
        boolean exists = companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, newCompanyId)
                .eq(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")) > 0;
        if (!exists) {
            throw new BusinessException("你不是该公司成员");
        }
        return buildMe(userId, newCompanyId);
    }

    @Transactional
    public MePayload bindCompany(BindCompanyReqVO request) {
        long userId = AuthContext.userId();
        long companyId = parseId(request.id());
        CompanyDO company = companyMapper.selectById(companyId);
        if (company == null) {
            throw new BusinessException("企业不存在，请先提交企业资料");
        }
        if (!company.getName().equals(request.name())
                || !company.getCreditCode().equals(request.creditCode())
                || !company.getLegalPersonName().equals(request.legalPersonName())) {
            throw new BusinessException("企业资料与已提交记录不一致");
        }

        CompanyMemberDO existing = companyMemberMapper.selectOne(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null) {
            return buildMe(userId, companyId);
        }

        if (company.getCreatedBy() == null || company.getCreatedBy() != userId) {
            throw new BusinessException("该企业已存在，请通过企业邀请码加入或提交人工认领申请");
        }

        CompanyMemberDO member = new CompanyMemberDO();
        member.setCompanyId(companyId);
        member.setUserId(userId);
        member.setRoleCode("LEGAL_CANDIDATE");
        member.setIsLegalPerson(false);
        member.setIsAdministrator(false);
        member.setStatus("PENDING");
        companyMemberMapper.insert(member);
        return buildMe(userId, companyId);
    }

    public void logout(String authorization) {
        authSessionService.revoke(authorization);
    }

    public List<DevUser> listDevUsers() {
        return companyMemberMapper.selectDevUsers().stream()
                .map(row -> {
                    String roleCode = string(row.get("roleCode"));
                    return new DevUser(String.valueOf(row.get("id")), string(row.get("nickname")), string(row.get("phone")),
                            roleCode, rolePermissionService.roleText(roleCode));
                })
                .toList();
    }

    public LoginSession switchUser(SwitchUserReqVO request) {
        long userId = parseId(request.userId());
        MemberInfo member = loadMemberAnyCompany(userId);
        if (member == null) {
            return null;
        }
        List<CompanyRole> companies = loadUserCompanies(userId);
        String currentCompanyId = companies.isEmpty() ? null : companies.get(0).companyId();
        UserProfile user = new UserProfile(member.userId(), "dev-openid", member.phone(), member.userName(), currentCompanyId, member.roleCode());
        return new LoginSession(tokenFor(userId), user);
    }

    public MemberInfo loadMember(long userId, long companyId) {
        return toMemberInfo(companyMemberMapper.selectMemberInfo(userId, companyId), companyId);
    }

    public MemberInfo loadMemberAnyCompany(long userId) {
        Map<String, Object> row = companyMemberMapper.selectMemberInfoAnyCompany(userId);
        Long companyId = row == null || row.get("companyId") == null ? null : Long.parseLong(String.valueOf(row.get("companyId")));
        return toMemberInfo(row, companyId);
    }

    public List<CompanyRole> loadUserCompanies(long userId) {
        return companyMemberMapper.selectUserCompanies(userId).stream()
                .map(row -> {
                    String roleCode = string(row.get("roleCode"));
                    return new CompanyRole(String.valueOf(row.get("companyId")), string(row.get("companyName")),
                            roleCode, row.get("roleCodes") != null
                                    ? accessControlService.effectiveRole(parseId(String.valueOf(row.get("companyId"))), userId).name()
                                    : row.get("roleName") == null
                                    ? rolePermissionService.roleText(roleCode)
                                    : string(row.get("roleName")));
                })
                .toList();
    }

    private MePayload buildMe(long userId, Long companyId) {
        List<CompanyRole> companies = loadUserCompanies(userId);
        Long effectiveCompanyId = companyId;
        if (effectiveCompanyId == null && !companies.isEmpty()) {
            effectiveCompanyId = parseId(companies.get(0).companyId());
        }
        MemberInfo member = loadMember(userId, effectiveCompanyId == null ? 0L : effectiveCompanyId);
        if (member == null || member.roleCode() == null) {
            SysUserDO user = sysUserMapper.selectById(userId);
            String nick = user == null ? "微信用户" : user.getNickname();
            String phone = user == null ? "" : user.getPhone();
            UserProfile profile = new UserProfile(String.valueOf(userId), "demo-openid", phone, nick, null, "GUEST");
            return new MePayload(profile, fallbackCompany(), null, companies);
        }
        CompanyProfile company = effectiveCompanyId == null ? null : toCompanyProfile(companyMapper.selectById(effectiveCompanyId));
        UserProfile profile = new UserProfile(member.userId(), "demo-openid", member.phone(), member.userName(),
                effectiveCompanyId == null ? null : String.valueOf(effectiveCompanyId), member.roleCode());
        return new MePayload(profile, company != null ? company : fallbackCompany(), member, companies);
    }

    private MemberInfo toMemberInfo(Map<String, Object> row, Long companyId) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        String roleCode = string(row.get("roleCode"));
        String status = string(row.get("status"));
        AccessControlOperations.EffectiveRole role = companyId == null || !"ACTIVE".equals(status)
                ? new AccessControlOperations.EffectiveRole("GUEST", "访客", List.of())
                : accessControlService.effectiveRole(companyId, Long.parseLong(String.valueOf(row.get("userId"))));
        return new MemberInfo(
                String.valueOf(row.get("userId")),
                string(row.get("nickname")),
                string(row.get("phone")),
                roleCode != null ? roleCode : "GUEST",
                role.name(),
                role.permissions(),
                status != null ? status : "NONE"
        );
    }

    public CompanyProfile toCompanyProfile(CompanyDO company) {
        if (company == null) {
            return null;
        }
        return new CompanyProfile(String.valueOf(company.getId()), company.getName(), company.getCreditCode(),
                company.getLegalPersonName(), company.getRegisteredAddress(), company.getContactPhone(),
                company.getBankName(), company.getBankAccount(), company.getCertificationStatus(), company.getRealNameStatus(),
                company.getFaceStatus(), company.getSealStatus());
    }

    private CompanyProfile fallbackCompany() {
        return new CompanyProfile("", "未加入企业", "", "", null, null, null, null,
                "NOT_SUBMITTED", "NOT_STARTED", "NOT_STARTED", "NOT_UPLOADED");
    }

    private String tokenFor(long userId) {
        return authSessionService.issue(userId);
    }

    private long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            throw new BusinessException("ID 格式不正确");
        }
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
