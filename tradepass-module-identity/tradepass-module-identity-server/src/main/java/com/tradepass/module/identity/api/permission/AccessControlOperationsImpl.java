package com.tradepass.module.identity.api.permission;

import com.tradepass.framework.common.pojo.TradePassDtos;
import com.tradepass.framework.common.pojo.TradePassDtos.MemberRole;
import java.util.List;
import com.tradepass.module.identity.service.permission.AccessControlService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class AccessControlOperationsImpl implements AccessControlOperations {
    private final AccessControlService delegate;
    public AccessControlOperationsImpl(@Lazy AccessControlService delegate) { this.delegate = delegate; }
    @Override public boolean isActiveMember(long companyId, long userId) { return delegate.isActiveMember(companyId, userId); }
    @Override public Long resolveCompanyId(String companyId) { return delegate.resolveCompanyId(companyId); }
    @Override public void requireManager(long companyId) { delegate.requireManager(companyId); }
    @Override public void requireLegal(long companyId) { delegate.requireLegal(companyId); }
    @Override public void requireMemberOrClaim(long companyId) { delegate.requireMemberOrClaim(companyId); }
    @Override public CompanyProfileAccess requireCompanyProfileAccess(long targetCompanyId) { return delegate.requireCompanyProfileAccess(targetCompanyId); }
    @Override public void requireLegalOrClaim(long companyId) { delegate.requireLegalOrClaim(companyId); }
    @Override public void requireCertificationOperator(long companyId) { delegate.requireCertificationOperator(companyId); }
    @Override public void requirePermission(long companyId, String permission) { delegate.requirePermission(companyId, permission); }
    @Override public void requireAnyPermission(long companyId, String... permissions) { delegate.requireAnyPermission(companyId, permissions); }
    @Override public boolean hasPermission(long companyId, String permission) { return delegate.hasPermission(companyId, permission); }
    @Override public EffectiveRole effectiveRole(long companyId, long userId) { return delegate.effectiveRole(companyId, userId); }
}
