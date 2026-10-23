package com.tradepass.module.identity.service.company;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.TradePassDtos.CompanyProfile;
import com.tradepass.module.identity.controller.app.company.vo.CompanySubmitReqVO;
import com.tradepass.module.identity.dal.dataobject.certification.CompanyCertificationApplicationDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyMemberDO;
import com.tradepass.module.identity.dal.dataobject.company.CompanyOnboardingAttemptDO;
import com.tradepass.module.identity.dal.dataobject.fadada.FadadaCorpIdentityDO;
import com.tradepass.module.identity.dal.mysql.certification.CompanyCertificationApplicationMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyMemberMapper;
import com.tradepass.module.identity.dal.mysql.company.CompanyOnboardingAttemptMapper;
import com.tradepass.module.identity.dal.mysql.fadada.FadadaCorpIdentityMapper;
import com.tradepass.module.identity.service.fadada.FadadaPersonalIdentityService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Independent applicant drafts; only a bounded, company-locked attempt may grant membership. */
@Service
public class CompanyOnboardingAttemptService {
    private static final long LEASE_MINUTES = 30;
    private static final String EXPIRED = "本次企业认证申请已失效，请重新申请";
    private final CompanyMapper companies;
    private final CompanyOnboardingAttemptMapper attempts;
    private final CompanyMemberMapper members;
    private final FadadaCorpIdentityMapper identities;
    private final CompanyCertificationApplicationMapper applications;
    private final FadadaPersonalIdentityService personal;

    public CompanyOnboardingAttemptService(CompanyMapper companies, CompanyOnboardingAttemptMapper attempts,
            CompanyMemberMapper members, FadadaCorpIdentityMapper identities,
            CompanyCertificationApplicationMapper applications, FadadaPersonalIdentityService personal) {
        this.companies = companies; this.attempts = attempts; this.members = members;
        this.identities = identities; this.applications = applications; this.personal = personal;
    }

    public record Activation(CompanyOnboardingAttemptDO attempt, boolean changed) { }

    /** A historical approval or membership must not be undone by a failed renewal. */
    @Transactional
    public boolean isEstablished(CompanyDO company) {
        company = lockedCompany(company.getId());
        if ("VERIFIED".equals(company.getCertificationStatus())) return true;
        if (members.selectCount(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, company.getId()).eq(CompanyMemberDO::getStatus, "ACTIVE").last("FOR UPDATE")) > 0) return true;
        FadadaCorpIdentityDO identity = identity(company.getId());
        if (identity != null && ("VERIFIED".equals(identity.getLocalStatus()) || identity.getVerifiedAt() != null)) return true;
        return applications.selectCount(new LambdaQueryWrapper<CompanyCertificationApplicationDO>()
                .eq(CompanyCertificationApplicationDO::getCompanyId, company.getId())
                .eq(CompanyCertificationApplicationDO::getStatus, "APPROVED").last("FOR UPDATE")) > 0;
    }

    @Transactional
    public CompanyOnboardingAttemptDO draft(long companyId, long userId, CompanySubmitReqVO request) {
        CompanyDO company = lockedCompany(companyId);
        requireVerifiedUser(userId);
        if (isEstablished(company)) throw new BusinessException("企业已正式开通，请通过企业邀请或独立认领流程加入");
        if (!Objects.equals(company.getCreditCode(), request.creditCode())) throw new BusinessException("企业统一社会信用代码不一致");
        preserveLegacyLease(company);
        CompanyOnboardingAttemptDO attempt = latestPending(companyId, userId);
        if (attempt != null && "ACTIVE".equals(attempt.getStatus())) {
            if (valid(attempt)) {
                if (!sameSnapshot(attempt, request)) throw new BusinessException("当前认证办理中，暂不能修改本次申请资料");
                return attempt;
            }
            expire(attempt);
            attempt = null;
        }
        if (attempt == null) attempt = newAttempt(company, userId);
        snapshot(attempt, request);
        if (attempt.getId() == null) {
            attempts.insert(attempt);
        } else {
            // Clearing an optional field is intentional. updateById would silently keep its old value.
            int updated = attempts.update(new LambdaUpdateWrapper<CompanyOnboardingAttemptDO>()
                    .eq(CompanyOnboardingAttemptDO::getId, attempt.getId())
                    .eq(CompanyOnboardingAttemptDO::getCompanyId, companyId)
                    .eq(CompanyOnboardingAttemptDO::getApplicantUserId, userId)
                    .eq(CompanyOnboardingAttemptDO::getStatus, "DRAFT")
                    .set(CompanyOnboardingAttemptDO::getCompanyName, attempt.getCompanyName())
                    .set(CompanyOnboardingAttemptDO::getCreditCode, attempt.getCreditCode())
                    .set(CompanyOnboardingAttemptDO::getLegalPersonName, attempt.getLegalPersonName())
                    .set(CompanyOnboardingAttemptDO::getRegisteredAddress, attempt.getRegisteredAddress())
                    .set(CompanyOnboardingAttemptDO::getContactPhone, attempt.getContactPhone())
                    .set(CompanyOnboardingAttemptDO::getBankName, attempt.getBankName())
                    .set(CompanyOnboardingAttemptDO::getBankAccount, attempt.getBankAccount()));
            if (updated != 1) throw new BusinessException("入驻申请状态已变化，请重新提交");
        }
        return attempt;
    }

    @Transactional
    public boolean hasAttempts(long companyId) {
        lockedCompany(companyId);
        return attempts.selectCount(new LambdaQueryWrapper<CompanyOnboardingAttemptDO>()
                .eq(CompanyOnboardingAttemptDO::getCompanyId, companyId).last("FOR UPDATE")) > 0;
    }

    @Transactional
    public boolean hasPendingAttempt(long companyId, long userId) {
        lockedCompany(companyId);
        CompanyOnboardingAttemptDO attempt = latestPending(companyId, userId);
        return attempt != null && valid(attempt);
    }

    @Transactional
    public CompanyOnboardingAttemptDO requireAttempt(long companyId, long userId) {
        lockedCompany(companyId);
        CompanyOnboardingAttemptDO attempt = latestPending(companyId, userId);
        if (attempt == null || !valid(attempt)) throw new BusinessException(EXPIRED);
        return attempt;
    }

    /** Expired pointers deliberately yield null; a late callback cannot revive the lease. */
    @Transactional
    public CompanyOnboardingAttemptDO current(long companyId) {
        lockedCompany(companyId);
        FadadaCorpIdentityDO identity = identity(companyId);
        if (identity == null || identity.getOnboardingAttemptId() == null) return null;
        CompanyOnboardingAttemptDO attempt = attemptById(identity.getOnboardingAttemptId());
        return attempt != null && Objects.equals(attempt.getCompanyId(), companyId)
                && "ACTIVE".equals(attempt.getStatus()) && valid(attempt) ? attempt : null;
    }

    @Transactional
    public boolean ownsCurrent(long companyId, long userId) {
        CompanyOnboardingAttemptDO attempt = current(companyId);
        return attempt != null && Objects.equals(attempt.getApplicantUserId(), userId);
    }

    @Transactional
    public CompanyOnboardingAttemptDO requireCurrent(long companyId, long userId) {
        CompanyOnboardingAttemptDO attempt = current(companyId);
        if (attempt == null || !Objects.equals(attempt.getApplicantUserId(), userId)) throw new BusinessException(EXPIRED);
        return attempt;
    }

    @Transactional
    public Activation activate(long companyId, long userId) {
        CompanyDO company = lockedCompany(companyId);
        requireVerifiedUser(userId);
        if (isEstablished(company)) throw new BusinessException("企业已正式开通，请通过企业邀请或独立认领流程加入");
        preserveLegacyLease(company);
        CompanyOnboardingAttemptDO own = requireAttempt(companyId, userId);
        FadadaCorpIdentityDO identity = identity(companyId);
        CompanyOnboardingAttemptDO occupying = identity == null || identity.getOnboardingAttemptId() == null
                ? null : attemptById(identity.getOnboardingAttemptId());
        if (occupying != null && "ACTIVE".equals(occupying.getStatus()) && valid(occupying)) {
            if (Objects.equals(occupying.getId(), own.getId())) return new Activation(occupying, false);
            throw new BusinessException("已有其他申请人正在办理企业认证，请在其取消或办理期限结束后重试（最长30分钟）");
        }
        if (occupying != null && "ACTIVE".equals(occupying.getStatus())) expire(occupying);
        // No timestamp means no actual provider flow started; its old candidate is not authority.
        if (identity != null && identity.getApplicantUserId() != null
                && !Objects.equals(identity.getApplicantUserId(), userId)) revokeCandidate(companyId, identity.getApplicantUserId());
        own.setStatus("ACTIVE");
        own.setStartedAt(LocalDateTime.now());
        own.setExpiresAt(own.getStartedAt().plusMinutes(LEASE_MINUTES));
        attempts.updateById(own);
        if (identity == null) {
            identity = new FadadaCorpIdentityDO();
            identity.setCompanyId(companyId);
            identity.setClientCorpId("tradepass-company-" + companyId);
            identity.setApplicantUserId(userId);
            identity.setOnboardingAttemptId(own.getId());
            identity.setAuthorizationUserId(userId);
            identity.setProviderRequestId(own.getProviderRequestId());
            identity.setLocalStatus("NOT_STARTED");
            identity.setBindingStatus("unauthorized");
            identity.setIdentStatus("unidentified");
            identity.setAuthScopes("[]");
            identities.insert(identity);
        } else resetIdentity(identity, own);
        if ("CANCELLED".equals(company.getCertificationStatus()) || "REJECTED".equals(company.getCertificationStatus())) {
            companies.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, companyId)
                    .set(CompanyDO::getCertificationStatus, "PENDING"));
        }
        return new Activation(own, true);
    }

    @Transactional
    public void complete(long companyId, long userId, String providerRequestId) {
        lockedCompany(companyId);
        CompanyOnboardingAttemptDO attempt = requireCurrent(companyId, userId);
        if (!Objects.equals(attempt.getProviderRequestId(), providerRequestId)) throw new BusinessException(EXPIRED);
        attempt.setStatus("COMPLETED");
        attempts.updateById(attempt);
        // Publish only the independently approved applicant's optional business details.
        // Provider-verified company/representative names were already stored by the caller.
        companies.update(new LambdaUpdateWrapper<CompanyDO>().eq(CompanyDO::getId, companyId)
                .set(CompanyDO::getRegisteredAddress, attempt.getRegisteredAddress())
                .set(CompanyDO::getContactPhone, attempt.getContactPhone())
                .set(CompanyDO::getBankName, attempt.getBankName())
                .set(CompanyDO::getBankAccount, attempt.getBankAccount()));
        attempts.update(new LambdaUpdateWrapper<CompanyOnboardingAttemptDO>()
                .eq(CompanyOnboardingAttemptDO::getCompanyId, companyId)
                .ne(CompanyOnboardingAttemptDO::getId, attempt.getId())
                .in(CompanyOnboardingAttemptDO::getStatus, List.of("DRAFT", "ACTIVE"))
                .set(CompanyOnboardingAttemptDO::getStatus, "SUPERSEDED"));
        members.delete(new LambdaQueryWrapper<CompanyMemberDO>()
                .eq(CompanyMemberDO::getCompanyId, companyId).ne(CompanyMemberDO::getUserId, userId)
                .eq(CompanyMemberDO::getStatus, "PENDING").eq(CompanyMemberDO::getRoleCode, "LEGAL_CANDIDATE"));
    }

    @Transactional
    public void cancel(long companyId, long userId) {
        CompanyDO company = lockedCompany(companyId);
        CompanyOnboardingAttemptDO attempt = latestPending(companyId, userId);
        if (attempt == null) throw new BusinessException("没有可取消的企业入驻申请");
        attempt.setStatus("CANCELLED");
        attempts.updateById(attempt);
        revokeCandidate(companyId, userId);
        FadadaCorpIdentityDO identity = identity(companyId);
        if (identity != null && Objects.equals(identity.getOnboardingAttemptId(), attempt.getId()) && !isEstablished(company)) {
            identities.update(new LambdaUpdateWrapper<FadadaCorpIdentityDO>().eq(FadadaCorpIdentityDO::getId, identity.getId())
                    .eq(FadadaCorpIdentityDO::getOnboardingAttemptId, attempt.getId())
                    .set(FadadaCorpIdentityDO::getOnboardingAttemptId, null)
                    .set(FadadaCorpIdentityDO::getLocalStatus, "CANCELLED")
                    .set(FadadaCorpIdentityDO::getFailureReason, "申请人已取消本次企业认证"));
        }
        // Company is a shared subject. Cancelling one draft never cancels someone else's flow.
    }

    public List<CompanyOnboardingAttemptDO> list(long userId) {
        return attempts.selectList(new LambdaQueryWrapper<CompanyOnboardingAttemptDO>()
                .eq(CompanyOnboardingAttemptDO::getApplicantUserId, userId)
                .orderByDesc(CompanyOnboardingAttemptDO::getId).last("LIMIT 100"));
    }

    public CompanyProfile profile(CompanyDO company, CompanyOnboardingAttemptDO attempt) {
        return new CompanyProfile(String.valueOf(company.getId()), attempt.getCompanyName(), attempt.getCreditCode(),
                attempt.getLegalPersonName(), attempt.getRegisteredAddress(), attempt.getContactPhone(),
                attempt.getBankName(), attempt.getBankAccount(),
                "ACTIVE".equals(attempt.getStatus()) ? "PENDING_REVIEW" : "PENDING",
                "NOT_STARTED", "NOT_STARTED", "NOT_UPLOADED");
    }

    private void preserveLegacyLease(CompanyDO company) {
        FadadaCorpIdentityDO identity = identity(company.getId());
        if (identity == null || identity.getOnboardingAttemptId() != null
                || !"IN_PROGRESS".equals(identity.getLocalStatus()) || identity.getApplicantUserId() == null) return;
        LocalDateTime started = identity.getAuthorizationSubmittedAt() == null ? identity.getSubmittedAt() : identity.getAuthorizationSubmittedAt();
        if (started == null || !started.plusMinutes(LEASE_MINUTES).isAfter(LocalDateTime.now())) return;
        CompanyOnboardingAttemptDO legacy = newAttempt(company, identity.getApplicantUserId());
        legacy.setStatus("ACTIVE"); legacy.setStartedAt(started); legacy.setExpiresAt(started.plusMinutes(LEASE_MINUTES));
        attempts.insert(legacy);
        identities.update(new LambdaUpdateWrapper<FadadaCorpIdentityDO>().eq(FadadaCorpIdentityDO::getId, identity.getId())
                .isNull(FadadaCorpIdentityDO::getOnboardingAttemptId)
                .set(FadadaCorpIdentityDO::getOnboardingAttemptId, legacy.getId())
                .set(FadadaCorpIdentityDO::getProviderRequestId, legacy.getProviderRequestId()));
    }

    private void resetIdentity(FadadaCorpIdentityDO identity, CompanyOnboardingAttemptDO attempt) {
        // updateById ignores null fields; explicit SETs are needed to discard every old applicant proof.
        identities.update(new LambdaUpdateWrapper<FadadaCorpIdentityDO>().eq(FadadaCorpIdentityDO::getId, identity.getId())
                .set(FadadaCorpIdentityDO::getOnboardingAttemptId, attempt.getId())
                .set(FadadaCorpIdentityDO::getApplicantUserId, attempt.getApplicantUserId())
                .set(FadadaCorpIdentityDO::getAuthorizationUserId, attempt.getApplicantUserId())
                .set(FadadaCorpIdentityDO::getProviderRequestId, attempt.getProviderRequestId())
                .set(FadadaCorpIdentityDO::getLocalStatus, "NOT_STARTED")
                .set(FadadaCorpIdentityDO::getSubmittedAt, null)
                .set(FadadaCorpIdentityDO::getAuthorizationSubmittedAt, null)
                .set(FadadaCorpIdentityDO::getAuthorizationConfirmedAt, null)
                .set(FadadaCorpIdentityDO::getLastSyncAt, null)
                .set(FadadaCorpIdentityDO::getLastReconciledAt, null)
                .set(FadadaCorpIdentityDO::getFailureReason, "")
                .set(FadadaCorpIdentityDO::getVerifiedAt, null)
                .set(FadadaCorpIdentityDO::getVerifiedName, null)
                .set(FadadaCorpIdentityDO::getVerifiedCreditCode, null)
                .set(FadadaCorpIdentityDO::getVerifiedLegalRepName, null)
                .set(FadadaCorpIdentityDO::getIdentMethod, null)
                .set(FadadaCorpIdentityDO::getOperatorId, null)
                .set(FadadaCorpIdentityDO::getOperatorType, null)
                .set(FadadaCorpIdentityDO::getSealSyncWarning, null));
    }

    private void expire(CompanyOnboardingAttemptDO attempt) {
        attempt.setStatus("EXPIRED"); attempts.updateById(attempt);
        revokeCandidate(attempt.getCompanyId(), attempt.getApplicantUserId());
    }

    private void revokeCandidate(long companyId, long userId) {
        members.delete(new LambdaQueryWrapper<CompanyMemberDO>().eq(CompanyMemberDO::getCompanyId, companyId)
                .eq(CompanyMemberDO::getUserId, userId).eq(CompanyMemberDO::getStatus, "PENDING")
                .eq(CompanyMemberDO::getRoleCode, "LEGAL_CANDIDATE"));
    }

    private CompanyOnboardingAttemptDO newAttempt(CompanyDO company, long userId) {
        CompanyOnboardingAttemptDO attempt = new CompanyOnboardingAttemptDO();
        attempt.setCompanyId(company.getId()); attempt.setApplicantUserId(userId); attempt.setStatus("DRAFT");
        attempt.setProviderRequestId("FDD-ATT-" + UUID.randomUUID().toString().replace("-", ""));
        snapshot(attempt, new CompanySubmitReqVO(null, company.getName(), company.getCreditCode(), company.getLegalPersonName(),
                company.getRegisteredAddress(), company.getContactPhone(), company.getBankName(), company.getBankAccount()));
        return attempt;
    }

    private CompanyOnboardingAttemptDO latestPending(long companyId, long userId) {
        return attempts.selectOne(new LambdaQueryWrapper<CompanyOnboardingAttemptDO>()
                .eq(CompanyOnboardingAttemptDO::getCompanyId, companyId)
                .eq(CompanyOnboardingAttemptDO::getApplicantUserId, userId)
                .in(CompanyOnboardingAttemptDO::getStatus, List.of("DRAFT", "ACTIVE"))
                .orderByDesc(CompanyOnboardingAttemptDO::getId).last("LIMIT 1 FOR UPDATE"));
    }

    private boolean valid(CompanyOnboardingAttemptDO attempt) {
        return "DRAFT".equals(attempt.getStatus()) || ("ACTIVE".equals(attempt.getStatus())
                && attempt.getExpiresAt() != null && attempt.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private CompanyOnboardingAttemptDO attemptById(long id) {
        return attempts.selectOne(new LambdaQueryWrapper<CompanyOnboardingAttemptDO>()
                .eq(CompanyOnboardingAttemptDO::getId, id).last("LIMIT 1 FOR UPDATE"));
    }

    private FadadaCorpIdentityDO identity(long companyId) {
        return identities.selectOne(new LambdaQueryWrapper<FadadaCorpIdentityDO>()
                .eq(FadadaCorpIdentityDO::getCompanyId, companyId).last("LIMIT 1 FOR UPDATE"));
    }

    private CompanyDO lockedCompany(long companyId) {
        CompanyDO company = companies.selectByIdForUpdate(companyId);
        if (company == null) throw new BusinessException("企业不存在");
        return company;
    }

    private void requireVerifiedUser(long userId) {
        if (AuthContext.userId() != userId || !"VERIFIED".equals(personal.requireCurrentVerified().status())) {
            throw new BusinessException("请先完成个人实名认证，再提交企业入驻申请");
        }
    }

    private void snapshot(CompanyOnboardingAttemptDO attempt, CompanySubmitReqVO request) {
        attempt.setCompanyName(request.name()); attempt.setCreditCode(request.creditCode());
        attempt.setLegalPersonName(request.legalPersonName()); attempt.setRegisteredAddress(request.registeredAddress());
        attempt.setContactPhone(request.contactPhone()); attempt.setBankName(request.bankName()); attempt.setBankAccount(request.bankAccount());
    }

    private boolean sameSnapshot(CompanyOnboardingAttemptDO attempt, CompanySubmitReqVO request) {
        return Objects.equals(attempt.getCompanyName(), request.name()) && Objects.equals(attempt.getCreditCode(), request.creditCode())
                && Objects.equals(attempt.getLegalPersonName(), request.legalPersonName())
                && Objects.equals(attempt.getRegisteredAddress(), request.registeredAddress())
                && Objects.equals(attempt.getContactPhone(), request.contactPhone())
                && Objects.equals(attempt.getBankName(), request.bankName()) && Objects.equals(attempt.getBankAccount(), request.bankAccount());
    }
}
