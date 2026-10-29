package com.tradepass.module.identity.api.user;


/** In-process domain contract; implementations retain the original transaction semantics. */
public interface UserIdentityOperations {
    record PaymentIdentity(String appId, String openid) { }
    public String currentDisplayName();

    /** Verified personal name only; returns empty when no verified identity exists. */
    public String verifiedName(long userId);

    public String requireCurrentVerifiedName(long companyId);
    public PaymentIdentity currentPaymentIdentity();
}
