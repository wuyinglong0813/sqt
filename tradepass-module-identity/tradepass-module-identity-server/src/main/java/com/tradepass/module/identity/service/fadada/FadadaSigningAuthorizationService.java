package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import static com.tradepass.framework.common.exception.SigningErrorCodes.*;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.framework.fadada.core.FadadaSealAuthorizationGateway;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/** Bridges a local signing assignment to one provider seal-use authorization. */
@Service
public class FadadaSigningAuthorizationService {
    private final AccessControlService access;
    private final FadadaSigningMemberService members;
    private final FadadaCompanyService companies;
    private final FadadaSealAuthorizationGateway seals;
    private ApplicationEventPublisher events;

    @Autowired
    public void setEvents(ApplicationEventPublisher events) { this.events = events; }

    public FadadaSigningAuthorizationService(AccessControlService access, FadadaSigningMemberService members,
                                            FadadaCompanyService companies, FadadaSealAuthorizationGateway seals) {
        this.access = access;
        this.members = members;
        this.companies = companies;
        this.seals = seals;
    }

    public ServiceUrlPayload memberSealGrantUrl(long companyId, long userId) {
        return memberSealGrantUrl(companyId, userId, null);
    }

    /** sealId comes from the contract domain's provider task, never a public request parameter. */
    public ServiceUrlPayload memberSealGrantUrl(long companyId, long userId, String sealId) {
        access.requireManager(companyId);
        access.requirePermission(companyId, "seal_manage");
        // Reconcile the assigned employee, not the manager currently issuing this request.
        // This also checks current ACTIVE membership and effective contract_sign permission.
        var member = members.ensureSigningMember(companyId, userId);
        var company = companies.requireVerified(companyId);
        long numericSealId = sealId == null ? configuredSealId(companyId) : numericSealId(sealId);
        if (hasEffectiveGrant(company.getOpenCorpId(), member.memberId(), numericSealId)) {
            requestDeliveryVerification(companyId, userId);
            return new ServiceUrlPayload(null, "sealGrant", "READY");
        }
        // clientUserId is the person operating the official grant page, while memberId
        // is its recipient. Local seal_manage permission does not confer the provider's
        // seal-management role; the official page still verifies the actual operator.
        String url = seals.memberSealGrantUrl(company.getOpenCorpId(), numericSealId, member.memberId(),
                "tradepass-user-" + AuthContext.userId());
        return new ServiceUrlPayload(url, "sealGrant", "AUTHORIZATION_REQUIRED");
    }

    /** Recheck delivery without issuing another grant link or granting permission. */
    public ServiceUrlPayload memberSealGrantStatus(long companyId, long userId) {
        access.requireManager(companyId);
        access.requirePermission(companyId, "seal_manage");
        var member = members.existingSigningMember(companyId, userId);
        var company = companies.requireVerified(companyId);
        boolean effective = hasEffectiveGrant(company.getOpenCorpId(), member.memberId(), configuredSealId(companyId));
        if (effective) requestDeliveryVerification(companyId, userId);
        return new ServiceUrlPayload(null, "sealGrant", effective ? "READY" : "AUTHORIZATION_REQUIRED");
    }

    private void requestDeliveryVerification(long companyId, long userId) {
        if (events != null) events.publishEvent(new SigningMemberSyncRequested(
                companyId, userId, AuthContext.userId(), "SEAL_GRANT_CONFIRMED"));
    }

    public void requireCurrentSigningAuthorization(long companyId) {
        access.requirePermission(companyId, "contract_sign");
        var member = members.ensureSigningMember(companyId, AuthContext.userId());
        requireEffectiveSigningSeal(companyId, member.memberId());
    }

    /** Reconcile the explicit desired signer without impersonating the original role grantor. */
    void reconcileLocalSigningAuthorization(long companyId, long userId) {
        var member = members.reconcileLocalSigningMember(companyId, userId);
        requireEffectiveSigningSeal(companyId, member.memberId());
    }

    private void requireEffectiveSigningSeal(long companyId, long memberId) {
        var company = companies.requireVerified(companyId);
        if (!hasEffectiveGrant(company.getOpenCorpId(), memberId, configuredSealId(companyId))) {
            throw new BusinessException(SEAL_AUTHORIZATION_REQUIRED, "本人尚未获得当前合同印章的有效用印授权，"
                    + "请企业授权人在商签通组织成员中完成用印确认，授权有效期间无需重复办理；"
                    + "系统确认电子签平台的实际授权生效后即可继续签署");
        }
    }

    private boolean hasEffectiveGrant(String openCorpId, long memberId, long sealId) {
        return seals.memberSealGrants(openCorpId, memberId).stream()
                .anyMatch(grant -> String.valueOf(sealId).equals(grant.sealId()) && "enable".equals(grant.sealStatus())
                        && "effective".equals(grant.grantStatus()));
    }

    private long configuredSealId(long companyId) {
        return numericSealId(companies.enabledSealId(companyId));
    }

    private long numericSealId(String value) {
        try {
            long sealId = Long.parseLong(value);
            if (sealId <= 0) throw new NumberFormatException();
            return sealId;
        } catch (NumberFormatException invalid) {
            throw new BusinessException(SEAL_CONFIGURATION_REQUIRED, "当前企业尚未配置有效的合同签署印章");
        }
    }
}
