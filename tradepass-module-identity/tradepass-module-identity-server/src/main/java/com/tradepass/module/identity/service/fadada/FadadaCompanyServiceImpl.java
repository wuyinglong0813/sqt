package com.tradepass.module.identity.service.fadada;

import com.tradepass.module.identity.service.certification.CompanyCertificationService;
import com.tradepass.module.identity.service.permission.AccessControlService;

import static com.tradepass.module.identity.api.fadada.FadadaCompanyOperations.*;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.fadada.config.FadadaProperties;
import com.tradepass.module.identity.api.fadada.dto.FadadaCompanyIdentityRespDTO;
import com.tradepass.module.identity.api.fadada.dto.LegalRepresentativeRespDTO;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpSealDO;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import com.tradepass.framework.fadada.core.FadadaCompanyGateway;
import com.tradepass.framework.fadada.core.FadadaCompanyQueryException;
import com.tradepass.framework.fadada.core.FadadaAuthorizationCallback;
import com.fasc.open.api.enums.corp.OperatorTypeEnum;
import com.tradepass.module.identity.service.certification.CompanyCertificationService.CertifiedApplicantRole;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpSealMapper;
import com.tradepass.module.identity.dal.mysql.user.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class FadadaCompanyServiceImpl implements FadadaCompanyService {
    private static final List<String> AUTH_SCOPES = List.of(
            "ident_info", "seal_info", "signtask_init", "signtask_info", "signtask_file");
    private static final String EXISTING_AUTHORIZATION_REASON =
            "企业已在电子签平台向本应用授权，正在核对已有授权关联和经办人身份；未核验完成前不会开通企业权限";
    private static final String AUTHORIZATION_ADMIN = "member_super_admin";
    private static final List<String> ONBOARDING_SCOPES = java.util.stream.Stream.concat(
            AUTH_SCOPES.stream(), java.util.stream.Stream.of("organization")).toList();
    private final FadadaCorpIdentityMapper identityMapper;
    private final FadadaCorpSealMapper sealMapper;
    private final CompanyMapper companyMapper;
    private final SysUserMapper userMapper;
    private final AccessControlService accessControl;
    private final CompanyCertificationService certificationService;
    private final FadadaPersonalIdentityService personalIdentityService;
    private final FadadaCompanyGateway gateway;
    private final FadadaProperties properties;
    private final ObjectMapper objectMapper;

    /** Operator facts from a verified corp-authorize callback; absent for queries. */
    private record OperatorEvidence(List<String> clientUserIds, String identMethod, Long memberId) {
        static final OperatorEvidence NONE = new OperatorEvidence(List.of(), null, null);
    }

    private record ApplicantRole(CertifiedApplicantRole role, String operatorType, String operatorId) { }

    public FadadaCompanyServiceImpl(FadadaCorpIdentityMapper identityMapper,
                                FadadaCorpSealMapper sealMapper,
                                CompanyMapper companyMapper,
                                SysUserMapper userMapper,
                                AccessControlService accessControl,
                                CompanyCertificationService certificationService,
                                FadadaPersonalIdentityService personalIdentityService,
                                FadadaCompanyGateway gateway,
                                FadadaProperties properties,
                                ObjectMapper objectMapper) {
        this.identityMapper = identityMapper;
        this.sealMapper = sealMapper;
        this.companyMapper = companyMapper;
        this.userMapper = userMapper;
        this.accessControl = accessControl;
        this.certificationService = certificationService;
        this.personalIdentityService = personalIdentityService;
        this.gateway = gateway;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public FadadaCompanyIdentityRespDTO current(long companyId) {
        accessControl.requireCertificationOperator(companyId);
        return payload(companyId, find(companyId));
    }

    @Transactional
    public ServiceUrlPayload createAuthUrl(long companyId) {
        requireReady();
        long userId = AuthContext.userId();
        accessControl.requireCertificationOperator(companyId);
        personalIdentityService.requireCurrentVerified();
        CompanyDO company = requireCompany(companyId);
        requireCompanyFields(company);
        FadadaCorpIdentityDO identity = ensure(companyId, userId);
        if ("CANCELLED".equals(identity.getLocalStatus())) {
            identity.setLocalStatus("NOT_STARTED");
            identity.setFailureReason("");
            identityMapper.updateById(identity);
        }
        if ("CANCELLED".equals(company.getCertificationStatus())) {
            companyMapper.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, companyId)
                    .set(CompanyDO::getCertificationStatus, "PENDING"));
            company.setCertificationStatus("PENDING");
        }
        // Native completion redirect plus client polling; the return page verifies server state.
        if ("VERIFIED".equals(identity.getLocalStatus())) {
            var result = syncCurrent(companyId);
            return new ServiceUrlPayload(null, "company", result.status());
        }
        SysUserDO currentUser = userMapper.selectById(userId);
        String url;
        LocalDateTime attemptStartedAt = LocalDateTime.now();
        try {
            url = gateway.createAuthUrl(new FadadaCompanyGateway.AuthCommand(
                identity.getClientCorpId(), clientUserId(userId), currentUser == null ? null : currentUser.getPhone(),
                company.getName(), company.getCreditCode(), ONBOARDING_SCOPES, properties.getCallbackUrl(),
                java.net.URLEncoder.encode("/pages/service-return/service-return?scene=company&companyId=" + companyId,
                        java.nio.charset.StandardCharsets.UTF_8)));
        } catch (FadadaCompanyQueryException exception) {
            if (!"210002".equals(exception.providerCode())) throw exception;
            // Already authorized is a cue to reconcile, never proof of this applicant's role.
            var result = syncCurrent(companyId);
            return new ServiceUrlPayload(null, "company", result.status());
        }
        validateUrl(url);
        identity.setLocalStatus("IN_PROGRESS");
        identity.setFailureReason("");
        identity.setSubmittedAt(attemptStartedAt);
        identityMapper.updateById(identity);
        if (!"VERIFIED".equals(company.getCertificationStatus())) {
            companyMapper.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, companyId)
                    .set(CompanyDO::getCertificationStatus, "PENDING_REVIEW"));
        }
        return new ServiceUrlPayload(url, "company", identity.getLocalStatus());
    }

    @Transactional
    public FadadaCompanyIdentityRespDTO syncCurrent(long companyId) {
        requireReady();
        accessControl.requireCertificationOperator(companyId);
        CompanyDO company = requireCompany(companyId);
        FadadaCorpIdentityDO identity = findForUpdate(companyId);
        if (identity == null) return payload(companyId, null);
        if (!"VERIFIED".equals(identity.getLocalStatus()) && identity.getLastSyncAt() != null
                && identity.getLastSyncAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
            return payload(companyId, identity);
        }
        try {
            if (existingAuthorization(identity)) markExistingAuthorizationPending(company, identity);
            return sync(companyId);
        } catch (FadadaCompanyQueryException exception) {
            if ("VERIFIED".equals(identity.getLocalStatus())) throw exception;
            // Commit the retry interval even on provider errors; callbacks can still sync immediately.
            identity.setLastSyncAt(LocalDateTime.now());
            identity.setFailureReason(exception.getMessage());
            identityMapper.updateById(identity);
            return payload(companyId, identity);
        }
    }

    @Transactional
    public ServiceUrlPayload createLegalRepresentativeUrl(long companyId) {
        var result = syncLegalRepresentative(companyId);
        if ("VERIFIED".equals(result.status())) return new ServiceUrlPayload(null, "legal", "VERIFIED");
        // /corp/change-identity-info is a PC-only business-information change for super admins.
        // It is not a mobile verification challenge and must not be exposed as one.
        throw new BusinessException(result.message());
    }

    @Transactional
    public LegalRepresentativeRespDTO syncLegalRepresentative(long companyId) {
        requireReady();
        long userId = AuthContext.userId();
        certificationService.requireLegalClaim(companyId, userId);
        personalIdentityService.requireCurrentVerified();
        CompanyDO company = requireCompany(companyId);
        FadadaCorpIdentityDO identity = findForUpdate(companyId);
        if (identity == null || !hasText(identity.getOpenCorpId())) {
            throw new BusinessException("请先完成企业认证");
        }
        FadadaCompanyGateway.CompanyAccount account = gateway.getCompany(identity.getClientCorpId(), identity.getOpenCorpId());
        FadadaCompanyGateway.CompanyIdentity detail = gateway.getIdentity(identity.getOpenCorpId());
        verifyMatches(company, detail);
        verifyAccount(identity, account, detail);
        ApplicantRole resolved = null;
        String requestId = "FDD-LEGAL-" + companyId + "-" + userId;
        String operatorIssue = null;
        try {
            resolved = resolveApplicantRole(identity, account, detail, userId, OperatorEvidence.NONE);
        } catch (BusinessException exception) {
            operatorIssue = exception.getMessage();
        }
        if (resolved == null || resolved.role() != CertifiedApplicantRole.LEGAL) {
            return new LegalRepresentativeRespDTO(String.valueOf(companyId),
                    operatorIssue == null ? "REVIEW_REQUIRED" : "IN_PROGRESS",
                    operatorIssue != null ? operatorIssue
                            : "企业管理身份已确认，但尚无可确认的本人法人身份记录。请联系平台支持补充核验；本页仅同步已有记录，重复刷新不会发起法人刷脸。");
        }
        certificationService.completeLegalClaim(companyId, userId, requestId);
        // Adopt the proven current operator only after legal membership was granted in this transaction.
        // The original application remains in the audit history; future callbacks follow this operator.
        identity.setApplicantUserId(userId);
        identity.setProviderRequestId(requestId);
        storeVerifiedIdentity(company, identity, account, detail, resolved);
        syncSealsBestEffort(identity);
        identity.setLastSyncAt(LocalDateTime.now());
        identityMapper.updateById(identity);
        return new LegalRepresentativeRespDTO(String.valueOf(companyId), "VERIFIED", "法人身份已核验，已开通法人权限");
    }

    @Transactional
    public FadadaCompanyIdentityRespDTO sync(long companyId) {
        return sync(companyId, OperatorEvidence.NONE);
    }

    private FadadaCompanyIdentityRespDTO sync(long companyId, OperatorEvidence evidence) {
        CompanyDO company = requireCompany(companyId);
        FadadaCorpIdentityDO identity = findForUpdate(companyId);
        if (identity == null) return payload(companyId, null);
        if (abandoned(company, identity)) return payload(companyId, identity);
        FadadaCompanyGateway.CompanyAccount account;
        try {
            account = gateway.getCompany(identity.getClientCorpId(), identity.getOpenCorpId());
        } catch (FadadaCompanyQueryException exception) {
            if (!"210032".equals(exception.providerCode())) throw exception;
            // Recover a missing provider ID via the SDK's credit-code lookup. All company,
            // authorization and operator checks below still apply before granting membership.
            account = gateway.getCompanyByCreditCode(company.getCreditCode());
        }
        return applyAccount(company, identity, account, evidence);
    }

    private FadadaCompanyIdentityRespDTO applyAccount(CompanyDO company, FadadaCorpIdentityDO identity,
                                                       FadadaCompanyGateway.CompanyAccount account,
                                                       OperatorEvidence evidence) {
        long companyId = company.getId();
        if (hasText(account.openCorpId())) identity.setOpenCorpId(account.openCorpId());
        if (hasText(account.bindingStatus())) identity.setBindingStatus(account.bindingStatus());
        if (hasText(account.identStatus())) identity.setIdentStatus(account.identStatus());
        if (account.authScopes() != null) identity.setAuthScopes(json(account.authScopes()));
        String authorizationIssue = null;
        if (!"enable".equalsIgnoreCase(account.availableStatus())) {
            authorizationIssue = "企业电子签账号未启用，暂未开通企业权限。请联系企业超管核对账号状态";
        } else if (!"authorized".equalsIgnoreCase(account.bindingStatus()) || account.authScopes() == null
                || !account.authScopes().containsAll(AUTH_SCOPES)) {
            authorizationIssue = "企业授权尚未完成，请继续办理企业认证与授权";
        }
        if (authorizationIssue != null) {
            identity.setLocalStatus("IN_PROGRESS");
            identity.setFailureReason(authorizationIssue);
            identity.setLastSyncAt(LocalDateTime.now());
            identityMapper.updateById(identity);
            return payload(companyId, identity);
        }
        if ("identified".equalsIgnoreCase(account.identStatus()) && hasText(account.openCorpId())) {
            FadadaCompanyGateway.CompanyIdentity detail = gateway.getIdentity(identity.getOpenCorpId());
            verifyMatches(company, detail);
            ApplicantRole resolved;
            try {
                resolved = resolveApplicantRole(identity, account, detail, identity.getApplicantUserId(), evidence);
            } catch (BusinessException exception) {
                identity.setLocalStatus("IN_PROGRESS");
                identity.setFailureReason(exception.getMessage());
                identity.setLastSyncAt(LocalDateTime.now());
                identityMapper.updateById(identity);
                return payload(companyId, identity);
            }
            storeVerifiedIdentity(company, identity, account, detail, resolved);
            CertifiedApplicantRole role = resolved.role();
            certificationService.completeProviderCertification(companyId, identity.getApplicantUserId(),
                    hasText(identity.getProviderRequestId()) ? identity.getProviderRequestId() : "FDD-CORP-" + identity.getClientCorpId(),
                    role == CertifiedApplicantRole.LEGAL ? "企业认证已完成，经办人为法人本人"
                            : AUTHORIZATION_ADMIN.equals(resolved.operatorType())
                                ? "企业授权已确认，开通管理员身份；法人身份需单独核验"
                                : "企业认证已完成，经办人为授权代理人，开通管理员身份", role);
            syncSealsBestEffort(identity);
        } else {
            identity.setLocalStatus("IN_PROGRESS");
        }
        identity.setLastSyncAt(LocalDateTime.now());
        identityMapper.updateById(identity);
        return payload(companyId, identity);
    }

    @Transactional
    public FadadaCompanyIdentityRespDTO syncByClientCorpId(String clientCorpId) {
        FadadaCorpIdentityDO identity = identityMapper.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                .eq(FadadaCorpIdentityDO::getClientCorpId, clientCorpId).last("LIMIT 1"));
        return identity == null ? null : sync(identity.getCompanyId());
    }

    @Transactional
    public FadadaCompanyIdentityRespDTO syncByOpenCorpId(String openCorpId) {
        FadadaCorpIdentityDO identity = identityMapper.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                .eq(FadadaCorpIdentityDO::getOpenCorpId, openCorpId).last("LIMIT 1"));
        return identity == null ? null : sync(identity.getCompanyId());
    }

    @Transactional
    public FadadaCompanyIdentityRespDTO syncCallback(String clientCorpId, String openCorpId, JsonNode data) {
        FadadaCorpIdentityDO identity = hasText(clientCorpId)
                ? identityMapper.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                    .eq(FadadaCorpIdentityDO::getClientCorpId, clientCorpId).last("LIMIT 1"))
                : identityMapper.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                    .eq(FadadaCorpIdentityDO::getOpenCorpId, openCorpId).last("LIMIT 1"));
        if (identity == null) return null;
        // Resolve the callback target first, then reload under the same company lock used by
        // legal claims. An earlier callback snapshot must not restore the former operator.
        long companyId = identity.getCompanyId();
        CompanyDO company = requireCompany(companyId);
        identity = findForUpdate(companyId);
        if (identity == null) return null;
        if (abandoned(company, identity)) return payload(companyId, identity);
        if (hasText(identity.getOpenCorpId()) && hasText(openCorpId)
                && !identity.getOpenCorpId().equals(openCorpId)) {
            throw new BusinessException("认证回调企业标识与当前企业不一致");
        }
        LocalDateTime eventTime = FadadaAuthorizationCallback.time(data);
        if ("VERIFIED".equals(identity.getLocalStatus()) && "success".equals(callbackText(data, "authResult"))
                && eventTime != null && identity.getVerifiedAt() != null
                && !eventTime.isAfter(identity.getVerifiedAt())) {
            return payload(companyId, identity);
        }
        boolean firstCompletion = "IN_PROGRESS".equals(identity.getLocalStatus()) && identity.getVerifiedAt() == null;
        if (FadadaAuthorizationCallback.completed(data, "corp-authorize", "corpIdentProcessStatus", AUTH_SCOPES)
                && identity.getClientCorpId().equals(callbackText(data, "clientCorpId"))
                && identity.getSubmittedAt() != null && !eventTime.isBefore(identity.getSubmittedAt().withNano(0))
                && (firstCompletion || identity.getLastSyncAt() == null || eventTime.isAfter(identity.getLastSyncAt()))
                && hasText(openCorpId) && openCorpId.equals(callbackText(data, "openCorpId"))
                && normalize(company.getCreditCode()).equals(normalize(callbackText(data, "corpIdentNo")))
                && normalize(company.getName()).equals(normalize(callbackText(data, "corpName")))) {
            // The signed callback already proves authorization. Query only identity details
            // for company matching and the operator's role; do not repeat the account lookup.
            return applyAccount(company, identity, new FadadaCompanyGateway.CompanyAccount(
                    identity.getClientCorpId(), openCorpId, "authorized", "identified", "enable",
                    FadadaAuthorizationCallback.scopes(data)), operatorEvidence(data));
        }
        if (hasText(openCorpId)) identity.setOpenCorpId(openCorpId);
        String authResult = callbackText(data, "authResult");
        String process = callbackText(data, "corpIdentProcessStatus", "verifyStatus");
        String reason = callbackText(data, "corpIdentFailedReason", "authFailedReason");
        if ("fail".equalsIgnoreCase(authResult) || "failed".equalsIgnoreCase(authResult)
                || "failed".equalsIgnoreCase(process)) {
            // FASC 5.1: exist means this app already has authorization, unlike reject.
            // Re-query the provider; a failed notification cannot prove the current operator.
            if (!"failed".equalsIgnoreCase(process)
                    && "exist".equalsIgnoreCase(callbackText(data, "authFailedReason"))) {
                markExistingAuthorizationPending(company, identity);
                return sync(companyId);
            }
            if ("failed".equalsIgnoreCase(process) && "exist".equalsIgnoreCase(reason)) {
                reason = "企业认证未通过";
            }
            // Callback delivery can be out of order. A fresh successful provider query takes
            // precedence over an old failure, and a transient query error is retried by the callback worker.
            if (identity.getVerifiedAt() != null || "VERIFIED".equals(identity.getLocalStatus())
                    || "VERIFIED".equals(company.getCertificationStatus())) {
                FadadaCompanyIdentityRespDTO latest = sync(identity.getCompanyId());
                if ("VERIFIED".equals(latest.status())) return latest;
                identity = findForUpdate(identity.getCompanyId());
            }
            identity.setLocalStatus("FAILED");
            identity.setFailureReason(hasText(reason) ? reason : "企业认证未通过");
            identity.setLastSyncAt(LocalDateTime.now());
            identityMapper.updateById(identity);
            companyMapper.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, identity.getCompanyId())
                    .set(CompanyDO::getCertificationStatus, "REJECTED"));
            return payload(identity.getCompanyId(), identity);
        }
        identityMapper.updateById(identity);
        // corpIdentProcessStatus is optional for an already identified company's authorization.
        // Keep the signed current-operator evidence while querying account/identity authoritatively.
        boolean currentAuthorization = "corp-authorize".equals(callbackText(data, "_verifiedEvent"))
                && "success".equalsIgnoreCase(authResult)
                && "enable".equals(callbackText(data, "availableStatus"))
                && identity.getClientCorpId().equals(callbackText(data, "clientCorpId"))
                && eventTime != null && identity.getSubmittedAt() != null
                && !eventTime.isBefore(identity.getSubmittedAt().withNano(0));
        return sync(identity.getCompanyId(), currentAuthorization
                ? operatorEvidence(data)
                : OperatorEvidence.NONE);
    }

    @Transactional
    public ServiceUrlPayload createSealManageUrl(long companyId) {
        requireReady();
        accessControl.requirePermission(companyId, "seal_manage");
        FadadaCorpIdentityDO identity = requireVerified(companyId);
        String url = gateway.createSealManageUrl(identity.getOpenCorpId(),
                clientUserId(AuthContext.userId()), "");
        validateUrl(url);
        return new ServiceUrlPayload(url, "seal", identity.getLocalStatus());
    }

    public FadadaCorpIdentityDO requireVerified(long companyId) {
        FadadaCorpIdentityDO identity = find(companyId);
        CompanyDO company = companyMapper.selectById(companyId);
        if (identity == null || company == null || !"VERIFIED".equals(company.getCertificationStatus())
                || !"VERIFIED".equals(identity.getLocalStatus()) || !hasText(identity.getOpenCorpId())) {
            throw new BusinessException("请先完成企业认证");
        }
        if (!normalize(company.getName()).equals(normalize(identity.getVerifiedName()))
                || !normalize(company.getCreditCode()).equals(normalize(identity.getVerifiedCreditCode()))) {
            throw new BusinessException("企业信息与认证记录不一致，请重新核验企业认证");
        }
        for (String scope : AUTH_SCOPES) {
            if (!hasText(identity.getAuthScopes()) || !identity.getAuthScopes().contains("\"" + scope + "\"")) {
                throw new BusinessException("企业电子签授权不完整，请重新进入企业认证完成授权");
            }
        }
        return identity;
    }

    public String enabledSealId(long companyId) {
        FadadaCorpSealDO seal = sealMapper.selectOne(new LambdaQueryWrapper<FadadaCorpSealDO>()
                .eq(FadadaCorpSealDO::getCompanyId, companyId)
                .eq(FadadaCorpSealDO::getSealStatus, "enable")
                .orderByAsc(FadadaCorpSealDO::getId).last("LIMIT 1"));
        if (seal == null) throw new BusinessException("请先启用企业电子印章");
        return seal.getSealId();
    }

    private void syncSealsBestEffort(FadadaCorpIdentityDO identity) {
        List<FadadaCompanyGateway.SealInfo> remote;
        try {
            remote = gateway.listSeals(identity.getOpenCorpId());
        } catch (RuntimeException exception) {
            // Do not treat a provider read failure as failed identity verification. No local
            // seal changes have occurred yet; the next refresh retries this separate step.
            identity.setSealSyncWarning("企业认证已通过，电子印章暂未同步，请稍后刷新或进入印章管理");
            return;
        }
        identity.setSealSyncWarning("");
        syncSeals(identity, remote);
    }

    private void syncSeals(FadadaCorpIdentityDO identity, List<FadadaCompanyGateway.SealInfo> remote) {
        LocalDateTime now = LocalDateTime.now();
        sealMapper.update(null, new LambdaUpdateWrapper<FadadaCorpSealDO>()
                .eq(FadadaCorpSealDO::getCompanyId, identity.getCompanyId())
                .set(FadadaCorpSealDO::getSealStatus, "disable")
                .set(FadadaCorpSealDO::getLastSyncAt, now));
        for (FadadaCompanyGateway.SealInfo value : remote) {
            if (!hasText(value.sealId())) continue;
            FadadaCorpSealDO seal = sealMapper.selectOne(new LambdaQueryWrapper<FadadaCorpSealDO>()
                    .eq(FadadaCorpSealDO::getCompanyId, identity.getCompanyId())
                    .eq(FadadaCorpSealDO::getSealId, value.sealId()).last("LIMIT 1"));
            if (seal == null) {
                seal = new FadadaCorpSealDO();
                seal.setCompanyId(identity.getCompanyId());
                seal.setSealId(value.sealId());
                seal.setSealName(value.sealName());
                seal.setCategoryType(value.categoryType());
                seal.setSealStatus(value.status());
                seal.setLastSyncAt(now);
                sealMapper.insert(seal);
            } else {
                seal.setSealName(value.sealName());
                seal.setCategoryType(value.categoryType());
                seal.setSealStatus(value.status());
                seal.setLastSyncAt(now);
                sealMapper.updateById(seal);
            }
        }
        boolean enabled = remote.stream().anyMatch(value -> "enable".equalsIgnoreCase(value.status()));
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, identity.getCompanyId())
                .set(CompanyDO::getSealStatus, enabled ? "VERIFIED" : "NOT_STARTED"));
    }

    private FadadaCorpIdentityDO ensure(long companyId, long userId) {
        FadadaCorpIdentityDO identity = findForUpdate(companyId);
        if (identity != null) return identity;
        identity = new FadadaCorpIdentityDO();
        identity.setCompanyId(companyId);
        identity.setApplicantUserId(userId);
        identity.setClientCorpId("tradepass-company-" + companyId);
        identity.setLocalStatus("NOT_STARTED");
        identity.setBindingStatus("unauthorized");
        identity.setIdentStatus("unidentified");
        identity.setAuthScopes(json(AUTH_SCOPES));
        identityMapper.insert(identity);
        return identity;
    }

    private FadadaCorpIdentityDO find(long companyId) {
        return identityMapper.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                .eq(FadadaCorpIdentityDO::getCompanyId, companyId).last("LIMIT 1"));
    }

    private FadadaCorpIdentityDO findForUpdate(long companyId) {
        // Current read: callbacks can establish a REPEATABLE_READ snapshot during their
        // initial identifier lookup, before waiting on the company lock.
        return identityMapper.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                .eq(FadadaCorpIdentityDO::getCompanyId, companyId).last("LIMIT 1 FOR UPDATE"));
    }

    private FadadaCompanyIdentityRespDTO payload(long companyId, FadadaCorpIdentityDO identity) {
        List<FadadaCompanyIdentityRespDTO.SealPayload> seals = sealMapper.selectList(
                        new LambdaQueryWrapper<FadadaCorpSealDO>().eq(FadadaCorpSealDO::getCompanyId, companyId)
                                .orderByAsc(FadadaCorpSealDO::getId))
                .stream().map(value -> new FadadaCompanyIdentityRespDTO.SealPayload(
                        value.getSealId(), value.getSealName(), value.getCategoryType(), value.getSealStatus())).toList();
        int enabled = (int) seals.stream().filter(value -> "enable".equalsIgnoreCase(value.status())).count();
        String status = identity == null ? "NOT_STARTED" : identity.getLocalStatus();
        boolean existing = existingAuthorization(identity) && "FAILED".equals(status);
        if (existing) status = "IN_PROGRESS"; // Also explain historical exist records without a data migration.
        return new FadadaCompanyIdentityRespDTO(properties.isEnabled(), String.valueOf(companyId), status,
                statusText(status), identity == null ? null : identity.getVerifiedName(),
                identity == null ? null : identity.getVerifiedCreditCode(),
                existing ? EXISTING_AUTHORIZATION_REASON
                        : identity == null || !hasText(identity.getFailureReason()) ? null : identity.getFailureReason(),
                enabled, seals, identity == null || identity.getLastSyncAt() == null
                ? null : identity.getLastSyncAt().toString(), identity == null ? null : identity.getSealSyncWarning());
    }

    private boolean existingAuthorization(FadadaCorpIdentityDO identity) {
        return identity != null && hasText(identity.getFailureReason())
                && "exist".equalsIgnoreCase(identity.getFailureReason().trim());
    }

    private void markExistingAuthorizationPending(CompanyDO company, FadadaCorpIdentityDO identity) {
        if (abandoned(company, identity) || "VERIFIED".equals(identity.getLocalStatus())
                || "VERIFIED".equals(company.getCertificationStatus())) return;
        identity.setLocalStatus("IN_PROGRESS");
        identity.setFailureReason(EXISTING_AUTHORIZATION_REASON);
        identityMapper.updateById(identity);
        if ("REJECTED".equals(company.getCertificationStatus())) {
            companyMapper.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, company.getId())
                    .eq(CompanyDO::getCertificationStatus, "REJECTED")
                    .set(CompanyDO::getCertificationStatus, "PENDING_REVIEW"));
            company.setCertificationStatus("PENDING_REVIEW");
        }
    }

    private void storeVerifiedIdentity(CompanyDO company, FadadaCorpIdentityDO identity,
                                       FadadaCompanyGateway.CompanyAccount account,
                                       FadadaCompanyGateway.CompanyIdentity detail, ApplicantRole resolved) {
        identity.setOpenCorpId(account.openCorpId());
        identity.setBindingStatus(account.bindingStatus());
        identity.setAuthScopes(json(account.authScopes()));
        identity.setIdentStatus(detail.identStatus());
        identity.setVerifiedName(detail.companyName());
        identity.setVerifiedCreditCode(detail.creditCode());
        identity.setVerifiedLegalRepName(detail.legalRepName());
        identity.setIdentMethod(detail.identMethod());
        identity.setOperatorType(resolved.operatorType());
        identity.setOperatorId(resolved.operatorId());
        identity.setVerifiedAt(parseTime(detail.verifiedAt(), LocalDateTime.now()));
        identity.setLocalStatus("VERIFIED");
        identity.setFailureReason("");
        // The verified company record is authoritative, even if the original form contained a typo.
        companyMapper.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, company.getId())
                .set(CompanyDO::getLegalPersonName, detail.legalRepName().trim()));
        company.setLegalPersonName(detail.legalRepName().trim());
    }

    private void verifyMatches(CompanyDO company, FadadaCompanyGateway.CompanyIdentity detail) {
        if (!normalize(company.getName()).equals(normalize(detail.companyName()))
                || !normalize(company.getCreditCode()).equals(normalize(detail.creditCode()))) {
            throw new BusinessException("认证信息与当前企业名称或统一社会信用代码不一致");
        }
        if (!hasText(detail.legalRepName())) throw new BusinessException("认证服务尚未返回法定代表人信息，请稍后刷新");
    }

    private ApplicantRole resolveApplicantRole(FadadaCorpIdentityDO identity,
                                               FadadaCompanyGateway.CompanyAccount account,
                                               FadadaCompanyGateway.CompanyIdentity detail, long applicantUserId,
                                               OperatorEvidence evidence) {
        verifyAccount(identity, account, detail);
        String applicantOpenUserId = personalIdentityService.verifiedOpenUserId(applicantUserId);
        boolean anotherOriginalOperator = false;
        if (hasText(detail.operatorId())
                && (detail.operatorOpenUserIdDocumented() || properties.isOperatorOpenUserIdConfirmed())) {
            if (applicantOpenUserId.equals(detail.operatorId())) {
                CertifiedApplicantRole role = roleOf(detail.operatorType());
                if (detail.operatorOpenUserIdDocumented()) {
                    // Official 5.0 binds the certification operator to a personal openUserId.
                    // Revalidate the current local account's live personal mapping before using it.
                    var person = personalIdentityService.verifiedPerson(applicantUserId);
                    if (person == null || !applicantOpenUserId.equals(person.openUserId())
                            || (role == CertifiedApplicantRole.LEGAL && (!hasText(person.name())
                            || !normalize(person.name()).equals(normalize(detail.legalRepName()))))) {
                        throw operatorMismatch();
                    }
                }
                return new ApplicantRole(role, detail.operatorType(), detail.operatorId());
            }
            // A different original certification operator does not disprove the current
            // applicant's independently verified administrator authorization.
            if (!detail.operatorOpenUserIdDocumented()) throw operatorMismatch();
            anotherOriginalOperator = true;
        }
        // Login account mappings and historical certification methods do not prove legal identity.
        String applicantClientUserId = clientUserId(applicantUserId);
        String identMethod = detail.identMethod();
        if (!evidence.clientUserIds().isEmpty()) {
            if (hasText(evidence.identMethod()) && !evidence.identMethod().equals(identMethod)) {
                throw new BusinessException("企业认证方式与授权通知不一致，暂未开通企业权限。请刷新认证状态或联系管理员核验");
            }
            if (!evidence.clientUserIds().contains(applicantClientUserId)) {
                throw new BusinessException("企业授权已完成，但本次授权经办人与当前小程序账号不一致，尚未确认本账号的企业管理权限。请按法大大页面提示申请超管权限后，以当前账号重新完成企业授权；仍未通过请联系平台支持核验");
            }
            if (evidence.memberId() == null || !account.authScopes().contains("organization")) {
                throw new BusinessException("企业实名已通过，但尚未取得当前经办人的企业管理权限证据，请由企业超管重新授权或联系管理员核验");
            }
            var member = gateway.getMember(account.openCorpId(), evidence.memberId());
            var person = personalIdentityService.verifiedPerson(applicantUserId);
            String fddId = person == null ? null : person.fddId();
            if (member == null || !evidence.memberId().equals(member.memberId())
                    || !"activated".equals(member.memberStatus()) || member.roleTypes() == null
                    || !member.roleTypes().contains("super_admin") || !hasText(fddId) || !fddId.equals(member.fddId())
                    || !applicantOpenUserId.equals(person.openUserId())) {
                throw new BusinessException("当前经办人未核验为该企业的有效超管，暂未开通企业权限，请联系企业超管或平台管理员");
            }
            return new ApplicantRole(CertifiedApplicantRole.ADMIN, AUTHORIZATION_ADMIN, applicantClientUserId);
        }
        // Reuse only administrator evidence. Granting LEGAL always needs a live operator proof;
        // an old local legal_rep marker must not substitute for missing provider fields.
        if (identity.getVerifiedAt() != null && hasText(identMethod) && identMethod.equals(identity.getIdentMethod())
                && hasText(identity.getOperatorId())) {
            if (!identity.getOperatorId().equals(applicantClientUserId)
                    && !identity.getOperatorId().equals(applicantOpenUserId)) throw operatorMismatch();
            if (roleOf(identity.getOperatorType()) == CertifiedApplicantRole.ADMIN) {
                return new ApplicantRole(CertifiedApplicantRole.ADMIN, identity.getOperatorType(), identity.getOperatorId());
            }
        }
        if (anotherOriginalOperator) {
            throw new BusinessException("该企业已在法大大实名，但原认证经办人不是当前账号，尚未确认本账号的企业管理权限。请按法大大页面提示申请超管权限后，以当前账号重新完成企业授权；仍未通过请联系平台支持核验");
        }
        throw new BusinessException("企业实名已通过，但认证服务尚未返回经办人身份，暂未开通企业权限。请稍后重新同步，仍未恢复请联系管理员核验");
    }

    private void verifyAccount(FadadaCorpIdentityDO identity, FadadaCompanyGateway.CompanyAccount account,
                               FadadaCompanyGateway.CompanyIdentity detail) {
        if (!"identified".equalsIgnoreCase(detail.identStatus())
                || !hasText(account.openCorpId()) || !account.openCorpId().equals(detail.openCorpId())) {
            throw new BusinessException("企业认证结果尚未确认，请刷新认证状态");
        }
        if (hasText(account.clientCorpId()) && !identity.getClientCorpId().equals(account.clientCorpId())) {
            throw new BusinessException("企业已有其他授权关联，暂未开通本次企业权限。请联系平台管理员核对原授权关联后继续办理");
        }
        if (!"authorized".equalsIgnoreCase(account.bindingStatus())
                || account.authScopes() == null || !account.authScopes().containsAll(AUTH_SCOPES)) {
            throw new BusinessException("企业授权尚未完成，请继续办理企业认证与授权");
        }
        if (!"enable".equalsIgnoreCase(account.availableStatus())) {
            throw new BusinessException("企业电子签账号未启用，暂未开通企业权限。请联系企业超管核对账号状态");
        }
    }

    private OperatorEvidence operatorEvidence(JsonNode data) {
        return new OperatorEvidence(FadadaAuthorizationCallback.clientUserIds(data),
                callbackText(data, "corpIdentMethod"), callbackMemberId(data));
    }

    private Long callbackMemberId(JsonNode data) {
        String value = callbackText(data, "memberId");
        try { long id = Long.parseLong(value); return id > 0 ? id : null; }
        catch (RuntimeException ignored) { return null; }
    }

    private CertifiedApplicantRole roleOf(String operatorType) {
        if (AUTHORIZATION_ADMIN.equals(operatorType)) return CertifiedApplicantRole.ADMIN;
        if (OperatorTypeEnum.LEGAL_REP.getCode().equals(operatorType)) return CertifiedApplicantRole.LEGAL;
        if (OperatorTypeEnum.DEPUTY_AUTH.getCode().equals(operatorType)) return CertifiedApplicantRole.ADMIN;
        throw new BusinessException("企业认证经办人身份类型尚未确认，请刷新结果或联系管理员核验");
    }

    private BusinessException operatorMismatch() {
        return new BusinessException("企业实名已通过，但经办人标识与当前账号的个人实名标识不一致，暂未开通企业权限。请联系管理员核验账号关联");
    }

    private static String clientUserId(long userId) { return "tradepass-user-" + userId; }

    private void requireCompanyFields(CompanyDO company) {
        if (!hasText(company.getName()) || !hasText(company.getCreditCode())) {
            throw new BusinessException("请先完善企业名称和统一社会信用代码");
        }
    }

    private boolean abandoned(CompanyDO company, FadadaCorpIdentityDO identity) {
        return "CANCELLED".equals(company.getCertificationStatus())
                || "CANCELLED".equals(identity.getLocalStatus());
    }

    private CompanyDO requireCompany(long companyId) {
        CompanyDO company = companyMapper.selectByIdForUpdate(companyId);
        if (company == null) throw new BusinessException("企业不存在");
        return company;
    }

    private void requireReady() {
        if (!properties.isEnabled() || !hasText(properties.getAppId()) || !hasText(properties.getAppSecret())
                || !hasText(properties.getServerUrl()) || !hasText(properties.getCallbackUrl())) {
            throw new BusinessException("电子签服务尚未配置完整");
        }
    }

    private void validateUrl(String value) {
        if (!hasText(value) || !value.startsWith("https://")) throw new BusinessException("电子签服务地址无效");
    }

    private String json(List<String> values) {
        try { return objectMapper.writeValueAsString(values); }
        catch (JsonProcessingException exception) { throw new BusinessException("认证授权范围保存失败"); }
    }

    private LocalDateTime parseTime(String value, LocalDateTime fallback) {
        return com.tradepass.framework.fadada.core.FadadaProviderTime.parse(value, fallback);
    }

    private String statusText(String status) {
        return switch (status == null ? "NOT_STARTED" : status) {
            case "IN_PROGRESS" -> "认证中";
            case "VERIFIED" -> "已认证";
            case "FAILED" -> "认证未通过";
            case "CANCELLED" -> "已取消";
            default -> "待认证";
        };
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
    private String callbackText(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull() && hasText(value.asText())) return value.asText().trim();
        }
        return null;
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
