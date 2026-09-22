package com.tradepass.module.identity.service.certification;

import com.tradepass.module.identity.service.company.TenantBootstrapService;
import com.tradepass.module.identity.service.permission.AccessControlService;

import com.tradepass.framework.audit.core.AuditLogService;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CompanyCertificationServiceImpl implements CompanyCertificationService {
    
    private final CompanyMapper companyMapper;
    private final CompanyMemberMapper companyMemberMapper;
    private final CompanyCertificationApplicationMapper applicationMapper;
    private final AccessControlService accessControlService;
    private final TenantBootstrapService tenantBootstrapService;
    private final AuditLogService auditLogService;
    private final boolean caMockEnabled;
    private final String callbackToken;

    public CompanyCertificationServiceImpl(CompanyMapper companyMapper,
                                       CompanyMemberMapper companyMemberMapper,
                                       CompanyCertificationApplicationMapper applicationMapper,
                                       AccessControlService accessControlService,
                                       TenantBootstrapService tenantBootstrapService,
                                       AuditLogService auditLogService,
                                       @Value("${tradepass.ca.mock-enabled:false}") boolean caMockEnabled,
                                       @Value("${tradepass.verification.callback-token:}") String callbackToken) {
        this.companyMapper = companyMapper;
        this.companyMemberMapper = companyMemberMapper;
        this.applicationMapper = applicationMapper;
        this.accessControlService = accessControlService;
        this.tenantBootstrapService = tenantBootstrapService;
        this.auditLogService = auditLogService;
        this.caMockEnabled = caMockEnabled;
        this.callbackToken = callbackToken == null ? "" : callbackToken;
    }

    @Transactional
    public CertificationApplicationRespDTO submit(long companyId) {
        long userId = AuthContext.userId();
        accessControlService.requireLegalOrClaim(companyId);
        CompanyDO company = requireCompany(companyId);
        CompanyCertificationApplicationDO pending = applicationMapper.selectOne(
                new LambdaQueryWrapper<CompanyCertificationApplicationDO>()
                        .eq(CompanyCertificationApplicationDO::getCompanyId, companyId)
                        .eq(CompanyCertificationApplicationDO::getApplicantUserId, userId)
                        .eq(CompanyCertificationApplicationDO::getStatus, "SUBMITTED")
                        .orderByDesc(CompanyCertificationApplicationDO::getId)
                        .last("LIMIT 1"));
        if (pending != null) {
            return toPayload(pending, company.getName());
        }
        if ("VERIFIED".equals(company.getCertificationStatus())) {
            throw new BusinessException("企业已完成认证");
        }
        if (caMockEnabled && (!"VERIFIED".equals(company.getRealNameStatus())
                || !"VERIFIED".equals(company.getFaceStatus()))) {
            throw new BusinessException("请先完成实名和人脸核验");
        }

        CompanyCertificationApplicationDO application = new CompanyCertificationApplicationDO();
        application.setCompanyId(companyId);
        application.setApplicantUserId(userId);
        String requestPrefix = caMockEnabled ? "MOCK-CA-" : "CERT-";
        application.setProviderRequestId(requestPrefix + UUID.randomUUID().toString().replace("-", ""));
        application.setStatus("SUBMITTED");
        application.setSubmittedAt(LocalDateTime.now());
        applicationMapper.insert(application);
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, companyId)
                .set(CompanyDO::getCertificationStatus, "PENDING_REVIEW"));
        auditLogService.log(companyId, "COMPANY_CERTIFICATION", application.getId(), "SUBMIT", "提交企业认证审核");

        if (caMockEnabled) {
            approve(application, company, "体验测试模拟认证自动审核", CertifiedApplicantRole.LEGAL);
        }
        return toPayload(application, company.getName());
    }

    @Transactional
    public CertificationApplicationRespDTO submit(String companyId) {
        try {
            return submit(Long.parseLong(companyId));
        } catch (NumberFormatException e) {
            throw new BusinessException("企业 ID 格式不正确");
        }
    }

    public List<CertificationApplicationRespDTO> myApplications() {
        long userId = AuthContext.userId();
        return applicationMapper.selectList(new LambdaQueryWrapper<CompanyCertificationApplicationDO>()
                        .eq(CompanyCertificationApplicationDO::getApplicantUserId, userId)
                        .orderByDesc(CompanyCertificationApplicationDO::getCreatedAt))
                .stream()
                .map(application -> {
                    CompanyDO company = companyMapper.selectById(application.getCompanyId());
                    return toPayload(application, company == null ? "未知企业" : company.getName());
                })
                .toList();
    }

    @Transactional
    public void completeProviderCertification(long companyId, long applicantUserId,
                                              String providerRequestId, String reason, CertifiedApplicantRole role) {
        if (role == null) throw new BusinessException("企业认证经办人身份尚未确认");
        CompanyDO company = requireCompany(companyId);
        CompanyCertificationApplicationDO application = applicationMapper.selectOne(
                new LambdaQueryWrapper<CompanyCertificationApplicationDO>()
                        .eq(CompanyCertificationApplicationDO::getProviderRequestId, providerRequestId)
                        .last("LIMIT 1"));
        if (application == null) {
            application = new CompanyCertificationApplicationDO();
            application.setCompanyId(companyId);
            application.setApplicantUserId(applicantUserId);
            application.setProviderRequestId(providerRequestId);
            application.setStatus("SUBMITTED");
            application.setSubmittedAt(LocalDateTime.now());
            applicationMapper.insert(application);
        }
        if (application.getCompanyId() != companyId || application.getApplicantUserId() != applicantUserId) {
            throw new BusinessException("企业认证申请与本次经办人不一致");
        }
        if ("APPROVED".equals(application.getStatus())) {
            // A current provider result can restore certification after a failed renewal.
            // Do not re-create removed memberships or overwrite subsequently assigned roles.
            markCompanyVerified(companyId);
        } else if ("SUBMITTED".equals(application.getStatus()) || "REJECTED".equals(application.getStatus())) {
            approve(application, company, reason, role);
        } else {
            throw new BusinessException("企业认证申请状态无效，请联系管理员核验");
        }
    }

    /** The caller must hold the company lock for the check and the subsequent provider-backed promotion. */
    public void requireLegalClaim(long companyId, long userId) {
        requireCompany(companyId);
        if (companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId).eq(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")) != 1) {
            throw new BusinessException("请先通过企业成员邀请加入本企业，并等待管理员审批");
        }
        if (companyMemberMapper.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId).ne(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")
                .and(q -> q.eq(CompanyMemberDO::getRoleCode, "LEGAL").or().eq(CompanyMemberDO::getIsLegalPerson, true))) > 0) {
            throw new BusinessException("本企业已有法人，请联系现法人办理变更，不能通过补充核验替换");
        }
    }

    /** Called only after provider legal_rep + openUserId evidence has been checked. */
    @Transactional
    public void completeLegalClaim(long companyId, long userId, String providerRequestId) {
        requireLegalClaim(companyId, userId);
        int changed = companyMemberMapper.update(new LambdaUpdateWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId).eq(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "ACTIVE")
                .set(CompanyMemberDO::getRoleCode, "LEGAL").set(CompanyMemberDO::getRoleCodes, "[\"LEGAL\"]")
                .set(CompanyMemberDO::getIsLegalPerson, true).set(CompanyMemberDO::getIsAdministrator, false)
                .set(CompanyMemberDO::getCustomPermissions, null));
        if (changed != 1) throw new BusinessException("成员状态已变化，请刷新后重新核验");
        completeProviderCertification(companyId, userId, providerRequestId,
                "企业法人本人已通过认证服务核验并接手企业", CertifiedApplicantRole.LEGAL);
        auditLogService.logAs(companyId, userId, "COMPANY_CERTIFICATION", companyId,
                "LEGAL_CLAIM", "认证服务已核实法人身份及本人账号");
    }

    @Transactional
    public CertificationApplicationRespDTO review(String suppliedToken, CertificationReviewReqVO request) {
        requireValidCallbackToken(suppliedToken);
        CompanyCertificationApplicationDO application = applicationMapper.selectOne(
                new LambdaQueryWrapper<CompanyCertificationApplicationDO>()
                        .eq(CompanyCertificationApplicationDO::getProviderRequestId, request.providerRequestId())
                        .last("LIMIT 1"));
        if (application == null) {
            throw new BusinessException("认证申请不存在");
        }
        CompanyDO company = requireCompany(application.getCompanyId());
        if (!"SUBMITTED".equals(application.getStatus())) {
            if (request.decision().equals(application.getStatus())) {
                return toPayload(application, company.getName());
            }
            throw new BusinessException("认证申请已完成，不能重复变更结果");
        }
        if ("APPROVED".equals(request.decision())) {
            // This legacy callback carries no operator identity evidence. A shared secret alone
            // cannot prove that the applicant is the legal representative or an authorized agent.
            if (!caMockEnabled) throw new BusinessException("请通过企业认证状态同步核验经办人身份后开通企业");
            approve(application, company, request.reason(), CertifiedApplicantRole.LEGAL);
        } else {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new BusinessException("驳回认证时必须填写原因");
            }
            reject(application, company, request.reason());
        }
        return toPayload(application, company.getName());
    }

    private void approve(CompanyCertificationApplicationDO application, CompanyDO company, String reason,
                         CertifiedApplicantRole role) {
        long companyId = company.getId();
        boolean legal = role == CertifiedApplicantRole.LEGAL;
        int memberUpdated = companyMemberMapper.update(new LambdaUpdateWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, application.getApplicantUserId())
                .eq(CompanyMemberDO::getRoleCode, "LEGAL_CANDIDATE")
                .eq(CompanyMemberDO::getStatus, "PENDING")
                .set(CompanyMemberDO::getRoleCode, role.name())
                .set(CompanyMemberDO::getRoleCodes, "[\"" + role.name() + "\"]")
                .set(CompanyMemberDO::getCustomPermissions, null)
                .set(CompanyMemberDO::getIsLegalPerson, legal)
                .set(CompanyMemberDO::getIsAdministrator, !legal)
                .set(CompanyMemberDO::getStatus, "ACTIVE"));
        boolean alreadyAssigned = memberUpdated == 0 && companyMemberMapper.selectCount(
                new LambdaQueryWrapper<CompanyMemberDO>()
                        .eq(CompanyMemberDO::getCompanyId, companyId)
                        .eq(CompanyMemberDO::getUserId, application.getApplicantUserId())
                        .eq(CompanyMemberDO::getRoleCode, role.name())
                        .eq(CompanyMemberDO::getStatus, "ACTIVE")) == 1;
        if (memberUpdated != 1 && !alreadyAssigned) {
            throw new BusinessException("企业认证申请人状态已变化，请人工复核");
        }
        markCompanyVerified(companyId);
        tenantBootstrapService.initialize(companyId, application.getApplicantUserId());
        finish(application, "APPROVED", reason);
        auditLogService.logAs(companyId, application.getApplicantUserId(), "COMPANY_CERTIFICATION",
                application.getId(), "APPROVE", safeReason(reason));
    }

    private void reject(CompanyCertificationApplicationDO application, CompanyDO company, String reason) {
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, company.getId())
                .set(CompanyDO::getCertificationStatus, "REJECTED"));
        finish(application, "REJECTED", reason);
        auditLogService.logAs(company.getId(), application.getApplicantUserId(), "COMPANY_CERTIFICATION",
                application.getId(), "REJECT", safeReason(reason));
    }

    private void finish(CompanyCertificationApplicationDO application, String status, String reason) {
        String previousStatus = application.getStatus();
        application.setStatus(status);
        application.setReviewReason(safeReason(reason));
        application.setReviewedAt(LocalDateTime.now());
        int updated = applicationMapper.update(new LambdaUpdateWrapper<CompanyCertificationApplicationDO>()
                .eq(CompanyCertificationApplicationDO::getId, application.getId())
                .eq(CompanyCertificationApplicationDO::getStatus, previousStatus)
                .set(CompanyCertificationApplicationDO::getStatus, status)
                .set(CompanyCertificationApplicationDO::getReviewReason, application.getReviewReason())
                .set(CompanyCertificationApplicationDO::getReviewedAt, application.getReviewedAt()));
        if (updated != 1) {
            throw new BusinessException("认证申请状态已变化，请勿重复处理");
        }
    }

    private void markCompanyVerified(long companyId) {
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>()
                .eq(CompanyDO::getId, companyId)
                .set(CompanyDO::getCertificationStatus, "VERIFIED")
                .set(CompanyDO::getRealNameStatus, "VERIFIED")
                .set(CompanyDO::getFaceStatus, "VERIFIED"));
    }

    private CompanyDO requireCompany(long companyId) {
        CompanyDO company = companyMapper.selectByIdForUpdate(companyId);
        if (company == null) {
            throw new BusinessException("企业不存在");
        }
        return company;
    }

    private void requireValidCallbackToken(String suppliedToken) {
        if (callbackToken.isBlank() || suppliedToken == null || !MessageDigest.isEqual(
                callbackToken.getBytes(StandardCharsets.UTF_8), suppliedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new BusinessException("认证回调凭证无效");
        }
    }

    private CertificationApplicationRespDTO toPayload(CompanyCertificationApplicationDO application, String companyName) {
        return new CertificationApplicationRespDTO(
                String.valueOf(application.getId()), String.valueOf(application.getCompanyId()), companyName,
                application.getProviderRequestId(), application.getStatus(), application.getReviewReason(),
                text(application.getSubmittedAt()), text(application.getReviewedAt()));
    }

    private String safeReason(String reason) {
        return reason == null ? "" : reason.trim();
    }

    private String text(LocalDateTime value) {
        return value == null ? null : value.toString();
    }
}
