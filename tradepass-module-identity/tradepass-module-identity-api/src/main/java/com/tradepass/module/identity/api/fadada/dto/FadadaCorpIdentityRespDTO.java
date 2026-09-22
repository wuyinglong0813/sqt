package com.tradepass.module.identity.api.fadada.dto;


import java.time.LocalDateTime;

public class FadadaCorpIdentityRespDTO {

    private Long id;
    private Long companyId;
    private Long applicantUserId;
    private String clientCorpId;
    private String openCorpId;
    private String localStatus;
    private String bindingStatus;
    private String identStatus;
    private String authScopes;
    private String identMethod;
    private String operatorType;
    private String operatorId;
    private String verifiedName;
    private String verifiedCreditCode;
    private String verifiedLegalRepName;
    private String failureReason;
    private String providerRequestId;
    private String sealSyncWarning;
    private LocalDateTime submittedAt;
    private LocalDateTime verifiedAt;
    private LocalDateTime lastSyncAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getApplicantUserId() { return applicantUserId; }
    public void setApplicantUserId(Long applicantUserId) { this.applicantUserId = applicantUserId; }
    public String getClientCorpId() { return clientCorpId; }
    public void setClientCorpId(String clientCorpId) { this.clientCorpId = clientCorpId; }
    public String getOpenCorpId() { return openCorpId; }
    public void setOpenCorpId(String openCorpId) { this.openCorpId = openCorpId; }
    public String getLocalStatus() { return localStatus; }
    public void setLocalStatus(String localStatus) { this.localStatus = localStatus; }
    public String getBindingStatus() { return bindingStatus; }
    public void setBindingStatus(String bindingStatus) { this.bindingStatus = bindingStatus; }
    public String getIdentStatus() { return identStatus; }
    public void setIdentStatus(String identStatus) { this.identStatus = identStatus; }
    public String getAuthScopes() { return authScopes; }
    public void setAuthScopes(String authScopes) { this.authScopes = authScopes; }
    public String getIdentMethod() { return identMethod; }
    public void setIdentMethod(String identMethod) { this.identMethod = identMethod; }
    public String getOperatorType() { return operatorType; }
    public void setOperatorType(String operatorType) { this.operatorType = operatorType; }
    public String getOperatorId() { return operatorId; }
    public void setOperatorId(String operatorId) { this.operatorId = operatorId; }
    public String getVerifiedName() { return verifiedName; }
    public void setVerifiedName(String verifiedName) { this.verifiedName = verifiedName; }
    public String getVerifiedCreditCode() { return verifiedCreditCode; }
    public void setVerifiedCreditCode(String verifiedCreditCode) { this.verifiedCreditCode = verifiedCreditCode; }
    public String getVerifiedLegalRepName() { return verifiedLegalRepName; }
    public void setVerifiedLegalRepName(String verifiedLegalRepName) { this.verifiedLegalRepName = verifiedLegalRepName; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String providerRequestId) { this.providerRequestId = providerRequestId; }
    public String getSealSyncWarning() { return sealSyncWarning; }
    public void setSealSyncWarning(String sealSyncWarning) { this.sealSyncWarning = sealSyncWarning; }
    public LocalDateTime getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(LocalDateTime submittedAt) { this.submittedAt = submittedAt; }
    public LocalDateTime getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(LocalDateTime verifiedAt) { this.verifiedAt = verifiedAt; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
