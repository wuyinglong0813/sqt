package com.tradepass.module.identity.dal.dataobject.company;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("company_invite")
public class CompanyInviteDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private String code;
    private String type;
    private String relationRole;
    private String targetPhoneHash;
    private String targetPhoneMasked;
    private Long invitedBy;
    private Boolean used;
    private Long usedBy;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getRelationRole() { return relationRole; }
    public void setRelationRole(String relationRole) { this.relationRole = relationRole; }
    public String getTargetPhoneHash() { return targetPhoneHash; }
    public void setTargetPhoneHash(String value) { this.targetPhoneHash = value; }
    public String getTargetPhoneMasked() { return targetPhoneMasked; }
    public void setTargetPhoneMasked(String value) { this.targetPhoneMasked = value; }
    public Long getInvitedBy() { return invitedBy; }
    public void setInvitedBy(Long value) { this.invitedBy = value; }
    public Boolean getUsed() { return used; }
    public void setUsed(Boolean used) { this.used = used; }
    public Long getUsedBy() { return usedBy; }
    public void setUsedBy(Long usedBy) { this.usedBy = usedBy; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
