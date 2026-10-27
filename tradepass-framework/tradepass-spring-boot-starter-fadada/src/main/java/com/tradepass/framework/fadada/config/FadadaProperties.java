package com.tradepass.framework.fadada.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "tradepass.fadada")
public class FadadaProperties {
    private boolean enabled;
    private String appId = "";
    private String appSecret = "";
    private String serverUrl = "https://api.fadada.com/api/v5";
    private String callbackUrl = "";
    // Operator-verified application owner only. Never populate these from onboarding input.
    private String ownerEnterpriseAppId = "";
    private String ownerEnterpriseOpenCorpId = "";
    private String ownerEnterpriseCreditCode = "";
    private String ownerEnterpriseClientCorpId = "";
    // The verified application owner pays provider usage while the contract parties sign.
    private boolean platformInitiatedSigningEnabled;
    // Apply only to first-time onboarding; existing enterprises may renew consent through administrators.
    private boolean legalRepresentativeOnlyOnboardingEnabled;
    // The public 5.1 document does not define the SDK extension's operatorId namespace.
    // Enable only after provider confirmation for this integration, never from user input.
    private boolean operatorOpenUserIdConfirmed;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = safe(appId); }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String appSecret) { this.appSecret = safe(appSecret); }
    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = safe(serverUrl); }
    public String getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(String callbackUrl) { this.callbackUrl = safe(callbackUrl); }
    public String getOwnerEnterpriseAppId() { return ownerEnterpriseAppId; }
    public void setOwnerEnterpriseAppId(String value) { this.ownerEnterpriseAppId = safe(value); }
    public String getOwnerEnterpriseOpenCorpId() { return ownerEnterpriseOpenCorpId; }
    public void setOwnerEnterpriseOpenCorpId(String value) { this.ownerEnterpriseOpenCorpId = safe(value); }
    public String getOwnerEnterpriseCreditCode() { return ownerEnterpriseCreditCode; }
    public void setOwnerEnterpriseCreditCode(String value) { this.ownerEnterpriseCreditCode = safe(value); }
    public String getOwnerEnterpriseClientCorpId() { return ownerEnterpriseClientCorpId; }
    public void setOwnerEnterpriseClientCorpId(String value) { this.ownerEnterpriseClientCorpId = safe(value); }
    public boolean isPlatformInitiatedSigningEnabled() { return platformInitiatedSigningEnabled; }
    public void setPlatformInitiatedSigningEnabled(boolean value) { this.platformInitiatedSigningEnabled = value; }
    public boolean isLegalRepresentativeOnlyOnboardingEnabled() { return legalRepresentativeOnlyOnboardingEnabled; }
    public void setLegalRepresentativeOnlyOnboardingEnabled(boolean value) { this.legalRepresentativeOnlyOnboardingEnabled = value; }
    public boolean isOperatorOpenUserIdConfirmed() { return operatorOpenUserIdConfirmed; }
    public void setOperatorOpenUserIdConfirmed(boolean value) { this.operatorOpenUserIdConfirmed = value; }

    /** Allows only the configured app owner's existing provider-side default binding. */
    public boolean matchesOwnerEnterprise(String openCorpId, String clientCorpId, String creditCode) {
        return !ownerEnterpriseAppId.isBlank() && ownerEnterpriseAppId.equals(appId)
                && !ownerEnterpriseOpenCorpId.isBlank() && ownerEnterpriseOpenCorpId.equals(openCorpId)
                && !ownerEnterpriseClientCorpId.isBlank() && ownerEnterpriseClientCorpId.equals(clientCorpId)
                && !ownerEnterpriseCreditCode.isBlank() && ownerEnterpriseCreditCode.equals(creditCode);
    }

    /** Resolve once before creating a task; persist this identity for recovery and downloads. */
    public String signingInitiatorOpenCorpId(String businessInitiatorOpenCorpId) {
        if (!platformInitiatedSigningEnabled) return businessInitiatorOpenCorpId;
        if (ownerEnterpriseAppId.isBlank() || !ownerEnterpriseAppId.equals(appId)
                || ownerEnterpriseOpenCorpId.isBlank() || ownerEnterpriseCreditCode.isBlank()
                || ownerEnterpriseClientCorpId.isBlank()) {
            throw new com.tradepass.framework.common.exception.BusinessException("平台签署配置未就绪，请联系平台管理员");
        }
        return ownerEnterpriseOpenCorpId;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
