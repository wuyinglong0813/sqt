package com.tradepass.module.identity.service.fadada;

import com.tradepass.framework.common.core.AuthContext;
import com.tradepass.framework.common.exception.BusinessException;
import com.tradepass.framework.common.pojo.ServiceUrlPayload;
import com.tradepass.framework.fadada.core.FadadaSealAuthorizationGateway;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.springframework.stereotype.Service;

/** Bridges a local signing assignment to one provider seal-use authorization. */
@Service
public class FadadaSigningAuthorizationService {
    private final AccessControlService access;
    private final FadadaSigningMemberService members;
    private final FadadaCompanyService companies;
    private final FadadaSealAuthorizationGateway seals;

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
            return new ServiceUrlPayload(null, "sealGrant", "READY");
        }
        String url = seals.memberSealGrantUrl(company.getOpenCorpId(), numericSealId, member.memberId(),
                "tradepass-user-" + AuthContext.userId());
        return new ServiceUrlPayload(url, "sealGrant", "AUTHORIZATION_REQUIRED");
    }

    public void requireCurrentSigningAuthorization(long companyId) {
        access.requirePermission(companyId, "contract_sign");
        var member = members.ensureSigningMember(companyId, AuthContext.userId());
        var company = companies.requireVerified(companyId);
        if (!hasEffectiveGrant(company.getOpenCorpId(), member.memberId(), configuredSealId(companyId))) {
            throw new BusinessException("本人已同步为电子签企业成员，但尚未获得当前合同印章的有效用印授权。"
                    + "请企业印章管理员在“企业 → 组织成员 → 授权签署”办理一次，授权有效期间无需重复办理");
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
            throw new BusinessException("当前企业尚未配置有效的合同签署印章");
        }
    }
}
