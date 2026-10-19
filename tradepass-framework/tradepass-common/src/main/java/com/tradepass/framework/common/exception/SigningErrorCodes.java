package com.tradepass.framework.common.exception;

/** Stable recovery states used by the signing flow across HTTP domain boundaries. */
public final class SigningErrorCodes {
    private SigningErrorCodes() {}

    public static final int COMPANY_AUTHORIZATION_REQUIRED = 46101;
    public static final int MEMBER_ACTIVATION_REQUIRED = 46102;
    public static final int PERSONAL_IDENTITY_REQUIRED = 46103;
    public static final int SEAL_AUTHORIZATION_REQUIRED = 46104;
    public static final int ADMIN_REVIEW_REQUIRED = 46105;
    public static final int COMPANY_IDENTITY_REQUIRED = 46106;
    public static final int SEAL_CONFIGURATION_REQUIRED = 46107;
}
