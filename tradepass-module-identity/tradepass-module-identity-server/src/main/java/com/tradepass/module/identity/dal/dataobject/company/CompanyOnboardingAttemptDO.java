package com.tradepass.module.identity.dal.dataobject.company;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** Applicant-owned onboarding snapshot; a draft grants no company authority. */
@TableName("company_onboarding_attempt")
public class CompanyOnboardingAttemptDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long applicantUserId;
    private String status;
    private String providerRequestId;
    private String companyName;
    private String creditCode;
    private String legalPersonName;
    private String registeredAddress;
    private String contactPhone;
    private String bankName;
    private String bankAccount;
    private LocalDateTime startedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long value) { this.id = value; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long value) { this.companyId = value; }
    public Long getApplicantUserId() { return applicantUserId; }
    public void setApplicantUserId(Long value) { this.applicantUserId = value; }
    public String getStatus() { return status; }
    public void setStatus(String value) { this.status = value; }
    public String getProviderRequestId() { return providerRequestId; }
    public void setProviderRequestId(String value) { this.providerRequestId = value; }
    public String getCompanyName() { return companyName; }
    public void setCompanyName(String value) { this.companyName = value; }
    public String getCreditCode() { return creditCode; }
    public void setCreditCode(String value) { this.creditCode = value; }
    public String getLegalPersonName() { return legalPersonName; }
    public void setLegalPersonName(String value) { this.legalPersonName = value; }
    public String getRegisteredAddress() { return registeredAddress; }
    public void setRegisteredAddress(String value) { this.registeredAddress = value; }
    public String getContactPhone() { return contactPhone; }
    public void setContactPhone(String value) { this.contactPhone = value; }
    public String getBankName() { return bankName; }
    public void setBankName(String value) { this.bankName = value; }
    public String getBankAccount() { return bankAccount; }
    public void setBankAccount(String value) { this.bankAccount = value; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime value) { this.startedAt = value; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime value) { this.expiresAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
