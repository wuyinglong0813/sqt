package com.tradepass.module.identity.api.user;


/** In-process domain contract; implementations retain the original transaction semantics. */
public interface UserIdentityOperations {
    record PaymentIdentity(String appId, String openid) { }
    public String currentDisplayName();

    public String requireCurrentVerifiedName(long companyId);
    public PaymentIdentity currentPaymentIdentity();
}
