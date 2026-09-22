package com.tradepass.module.identity.api.user;


/** In-process domain contract; implementations retain the original transaction semantics. */
public interface UserIdentityOperations {
    public String currentDisplayName();

    public String requireCurrentVerifiedName(long companyId);
}
