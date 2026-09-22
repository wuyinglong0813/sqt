package com.tradepass.module.identity.api.user;

import com.tradepass.module.identity.service.user.UserIdentityService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
public class UserIdentityOperationsImpl implements UserIdentityOperations {
    private final UserIdentityService delegate;
    public UserIdentityOperationsImpl(@Lazy UserIdentityService delegate) { this.delegate = delegate; }
    @Override public String currentDisplayName() { return delegate.currentDisplayName(); }
    @Override public String requireCurrentVerifiedName(long companyId) { return delegate.requireCurrentVerifiedName(companyId); }
}
