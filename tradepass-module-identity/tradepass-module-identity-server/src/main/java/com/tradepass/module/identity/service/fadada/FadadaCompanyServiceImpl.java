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
import com.tradepass.framework.fadada.core.FadadaCompanyGateway;
import com.tradepass.framework.fadada.core.FadadaCompanyQueryException;
import com.fasc.open.api.enums.corp.OperatorTypeEnum;
import com.tradepass.module.identity.service.certification.CompanyCertificationService.CertifiedApplicantRole;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpSealMapper;
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
    private final FadadaCorpIdentityMapper identityMapper;
    private final FadadaCorpSealMapper sealMapper;
    private final CompanyMapper companyMapper;
    private final AccessControlService accessControl;
    private final CompanyCertificationService certificationService;
    private final FadadaPersonalIdentityService personalIdentityService;
    private final FadadaCompanyGateway gateway;
    private final FadadaProperties properties;
    private final ObjectMapper objectMapper;

    public FadadaCompanyServiceImpl(FadadaCorpIdentityMapper identityMapper,
                                FadadaCorpSealMapper sealMapper,
                                CompanyMapper companyMapper,
                                AccessControlService accessControl,
                                CompanyCertificationService certificationService,
                                FadadaPersonalIdentityService personalIdentityService,
                                FadadaCompanyGateway gateway,
                                FadadaProperties properties,
                                ObjectMapper objectMapper) {
        this.identityMapper = identityMapper;
        this.sealMapper = sealMapper;
        this.companyMapper = companyMapper;
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
        accessControl.requireCertificationOperator(companyId);
        personalIdentityService.requireCurrentVerified();
        CompanyDO company = requireCompany(companyId);
        requireCompanyFields(company);
        FadadaCorpIdentityDO identity = ensure(companyId, AuthContext.userId());
        // Completion is handled by client polling; do not send an internal page path as a URL.
        if ("VERIFIED".equals(identity.getLocalStatus())) {
            var result = syncCurrent(companyId);
            return new ServiceUrlPayload(null, "company", result.status());
        }
        String url;
        try {
            url = gateway.createAuthUrl(new FadadaCompanyGateway.AuthCommand(
                identity.getClientCorpId(), "tradepass-user-" + AuthContext.userId(),
                company.getName(), company.getCreditCode(), AUTH_SCOPES, properties.getCallbackUrl(),
                null));
        } catch (FadadaCompanyQueryException exception) {
            if (!"210002".equals(exception.providerCode())) throw exception;
            // Already authorized is a cue to reconcile, never proof of this applicant's role.
            var result = syncCurrent(companyId);
            return new ServiceUrlPayload(null, "company", result.status());
        }
        validateUrl(url);
        identity.setLocalStatus("IN_PROGRESS");
        identity.setFailureReason("");
        identity.setSubmittedAt(LocalDateTime.now());
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
        requireCompany(companyId);
        FadadaCorpIdentityDO identity = findForUpdate(companyId);
        if (identity == null) return payload(companyId, null);
        if (!"VERIFIED".equals(identity.getLocalStatus()) && identity.getLastSyncAt() != null
                && identity.getLastSyncAt().isAfter(LocalDateTime.now().minusSeconds(30))) {
            return payload(companyId, identity);
        }
        try {
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
        requireReady();
        long userId = AuthContext.userId();
        certificationService.requireLegalClaim(companyId, userId);
        personalIdentityService.requireCurrentVerified();
        FadadaCorpIdentityDO identity = requireVerified(companyId);
        String url = gateway.createIdentityChangeUrl(identity.getClientCorpId(), identity.getOpenCorpId(),
                "tradepass-user-" + userId);
        validateUrl(url);
        return new ServiceUrlPayload(url, "legal", "IN_PROGRESS");
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
        try {
            if (resolveApplicantRole(identity, account, detail, userId) != CertifiedApplicantRole.LEGAL) {
                return new LegalRepresentativeRespDTO(String.valueOf(companyId), "IN_PROGRESS",
                        "当前认证记录为授权经办人，请由法人本人进入核验并选择法人本人认证");
            }
        } catch (BusinessException exception) {
            return new LegalRepresentativeRespDTO(String.valueOf(companyId), "IN_PROGRESS", exception.getMessage());
        }
        String requestId = "FDD-LEGAL-" + identity.getClientCorpId() + "-" + userId;
        certificationService.completeLegalClaim(companyId, userId, requestId);
        // Adopt the proven current operator only after legal membership was granted in this transaction.
        // The original application remains in the audit history; future callbacks follow this operator.
        identity.setApplicantUserId(userId);
        identity.setProviderRequestId(requestId);
        storeVerifiedIdentity(company, identity, account, detail);
        syncSealsBestEffort(identity);
        identity.setLastSyncAt(LocalDateTime.now());
        identityMapper.updateById(identity);
        return new LegalRepresentativeRespDTO(String.valueOf(companyId), "VERIFIED", "法人身份已核验，已开通法人权限");
    }

    @Transactional
    public FadadaCompanyIdentityRespDTO sync(long companyId) {
        CompanyDO company = requireCompany(companyId);
        FadadaCorpIdentityDO identity = findForUpdate(companyId);
        if (identity == null) return payload(companyId, null);
        FadadaCompanyGateway.CompanyAccount account;
        try {
            account = gateway.getCompany(identity.getClientCorpId(), identity.getOpenCorpId());
        } catch (FadadaCompanyQueryException exception) {
            if (!"210032".equals(exception.providerCode())) throw exception;
            // Recover a missing provider ID via the SDK's credit-code lookup. All company,
            // authorization and operator checks below still apply before granting membership.
            account = gateway.getCompanyByCreditCode(company.getCreditCode());
        }
        if (hasText(account.openCorpId())) identity.setOpenCorpId(account.openCorpId());
        if (hasText(account.bindingStatus())) identity.setBindingStatus(account.bindingStatus());
        if (hasText(account.identStatus())) identity.setIdentStatus(account.identStatus());
        if (account.authScopes() != null) identity.setAuthScopes(json(account.authScopes()));
        if ("identified".equalsIgnoreCase(account.identStatus()) && hasText(account.openCorpId())) {
            FadadaCompanyGateway.CompanyIdentity detail = gateway.getIdentity(identity.getOpenCorpId());
            verifyMatches(company, detail);
            CertifiedApplicantRole role;
            try {
                role = resolveApplicantRole(identity, account, detail);
            } catch (BusinessException exception) {
                identity.setLocalStatus("IN_PROGRESS");
                identity.setFailureReason(exception.getMessage());
                identity.setLastSyncAt(LocalDateTime.now());
                identityMapper.updateById(identity);
                return payload(companyId, identity);
            }
            storeVerifiedIdentity(company, identity, account, detail);
            certificationService.completeProviderCertification(companyId, identity.getApplicantUserId(),
                    hasText(identity.getProviderRequestId()) ? identity.getProviderRequestId() : "FDD-CORP-" + identity.getClientCorpId(),
                    role == CertifiedApplicantRole.LEGAL ? "企业认证已完成，经办人为法人本人" : "企业认证已完成，经办人为授权代理人，开通管理员身份", role);
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
        if (hasText(openCorpId)) identity.setOpenCorpId(openCorpId);
        String authResult = callbackText(data, "authResult");
        String process = callbackText(data, "corpIdentProcessStatus", "verifyStatus");
        String reason = callbackText(data, "corpIdentFailedReason", "authFailedReason");
        if ("fail".equalsIgnoreCase(authResult) || "failed".equalsIgnoreCase(authResult)
                || "failed".equalsIgnoreCase(process)) {
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
        return sync(identity.getCompanyId());
    }

    @Transactional
    public ServiceUrlPayload createSealManageUrl(long companyId) {
        requireReady();
        accessControl.requirePermission(companyId, "seal_manage");
        FadadaCorpIdentityDO identity = requireVerified(companyId);
        String url = gateway.createSealManageUrl(identity.getOpenCorpId(),
                "tradepass-user-" + AuthContext.userId(), "");
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
        return new FadadaCompanyIdentityRespDTO(properties.isEnabled(), String.valueOf(companyId), status,
                statusText(status), identity == null ? null : identity.getVerifiedName(),
                identity == null ? null : identity.getVerifiedCreditCode(),
                identity == null || !hasText(identity.getFailureReason()) ? null : identity.getFailureReason(),
                enabled, seals, identity == null || identity.getLastSyncAt() == null
                ? null : identity.getLastSyncAt().toString(), identity == null ? null : identity.getSealSyncWarning());
    }

    private void storeVerifiedIdentity(CompanyDO company, FadadaCorpIdentityDO identity,
                                       FadadaCompanyGateway.CompanyAccount account,
                                       FadadaCompanyGateway.CompanyIdentity detail) {
        identity.setOpenCorpId(account.openCorpId());
        identity.setBindingStatus(account.bindingStatus());
        identity.setAuthScopes(json(account.authScopes()));
        identity.setIdentStatus(detail.identStatus());
        identity.setVerifiedName(detail.companyName());
        identity.setVerifiedCreditCode(detail.creditCode());
        identity.setVerifiedLegalRepName(detail.legalRepName());
        identity.setIdentMethod(detail.identMethod());
        identity.setOperatorType(detail.operatorType());
        identity.setOperatorId(detail.operatorId());
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

    private CertifiedApplicantRole resolveApplicantRole(FadadaCorpIdentityDO identity,
                                                        FadadaCompanyGateway.CompanyAccount account,
                                                        FadadaCompanyGateway.CompanyIdentity detail) {
        return resolveApplicantRole(identity, account, detail, identity.getApplicantUserId());
    }

    private CertifiedApplicantRole resolveApplicantRole(FadadaCorpIdentityDO identity,
                                                        FadadaCompanyGateway.CompanyAccount account,
                                                        FadadaCompanyGateway.CompanyIdentity detail, long applicantUserId) {
        if (!"identified".equalsIgnoreCase(detail.identStatus())
                || !hasText(account.openCorpId()) || !account.openCorpId().equals(detail.openCorpId())
                || (hasText(account.clientCorpId()) && !identity.getClientCorpId().equals(account.clientCorpId()))) {
            throw new BusinessException("企业认证结果尚未确认，请刷新认证状态");
        }
        if (!"authorized".equalsIgnoreCase(account.bindingStatus())
                || account.authScopes() == null || !account.authScopes().containsAll(AUTH_SCOPES)) {
            throw new BusinessException("企业授权尚未完成，请继续办理企业认证与授权");
        }
        String applicantOpenUserId = personalIdentityService.verifiedOpenUserId(applicantUserId);
        if (!hasText(detail.operatorId()) || !applicantOpenUserId.equals(detail.operatorId())) {
            throw new BusinessException("认证经办人与当前申请账号尚未匹配，请使用申请人本人账号完成认证或联系管理员核验");
        }
        if (OperatorTypeEnum.LEGAL_REP.getCode().equals(detail.operatorType())) return CertifiedApplicantRole.LEGAL;
        if (OperatorTypeEnum.DEPUTY_AUTH.getCode().equals(detail.operatorType())) return CertifiedApplicantRole.ADMIN;
        throw new BusinessException("企业认证经办人身份类型尚未确认，请刷新结果或联系管理员核验");
    }

    private void requireCompanyFields(CompanyDO company) {
        if (!hasText(company.getName()) || !hasText(company.getCreditCode())) {
            throw new BusinessException("请先完善企业名称和统一社会信用代码");
        }
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
        if (!hasText(value)) return fallback;
        for (DateTimeFormatter formatter : List.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME,
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))) {
            try { return LocalDateTime.parse(value, formatter); } catch (RuntimeException ignored) { }
        }
        return fallback;
    }

    private String statusText(String status) {
        return switch (status == null ? "NOT_STARTED" : status) {
            case "IN_PROGRESS" -> "认证中";
            case "VERIFIED" -> "已认证";
            case "FAILED" -> "认证未通过";
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
