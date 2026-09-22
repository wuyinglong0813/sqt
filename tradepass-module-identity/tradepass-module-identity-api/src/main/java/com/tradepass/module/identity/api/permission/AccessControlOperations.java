package com.tradepass.module.identity.api.permission;

import com.tradepass.framework.common.pojo.TradePassDtos;

import com.tradepass.framework.common.pojo.TradePassDtos.MemberRole;
import java.util.List;

/** In-process domain contract; implementations retain the original transaction semantics. */
public interface AccessControlOperations {
    public record EffectiveRole(String code, String name, List<String> permissions, List<MemberRole> roles) {
        public EffectiveRole(String code, String name, List<String> permissions) {
            this(code, name, permissions, List.of(new MemberRole(code, name)));
        }
    }

    public enum CompanyProfileAccess {
        SENSITIVE_OWNER,
        MEMBER,
        COUNTERPARTY
    }

    public boolean isActiveMember(long companyId, long userId);

    public Long resolveCompanyId(String companyId);

    public void requireManager(long companyId);

    public void requireLegal(long companyId);

    public void requireMemberOrClaim(long companyId);

    public CompanyProfileAccess requireCompanyProfileAccess(long targetCompanyId);

    public void requireLegalOrClaim(long companyId);

    public void requireCertificationOperator(long companyId);

    public void requirePermission(long companyId, String permission);

    public void requireAnyPermission(long companyId, String... permissions);

    public boolean hasPermission(long companyId, String permission);

    public EffectiveRole effectiveRole(long companyId, long userId);
}
