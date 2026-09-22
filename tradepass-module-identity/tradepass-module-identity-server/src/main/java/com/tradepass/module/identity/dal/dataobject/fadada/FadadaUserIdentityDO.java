package com.tradepass.module.identity.dal.dataobject.fadada;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("fadada_user_identity")
public class FadadaUserIdentityDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private String clientUserId;
    private String openUserId;
    private String localStatus;
    private String bindingStatus;
    private String identStatus;
    private String identProcessStatus;
    private String authScopes;
    private String identMethod;
    private String verifiedName;
    private String failureReason;
    private LocalDateTime identSubmittedAt;
    private LocalDateTime identVerifiedAt;
    private LocalDateTime lastSyncAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getClientUserId() { return clientUserId; }
    public void setClientUserId(String clientUserId) { this.clientUserId = clientUserId; }
    public String getOpenUserId() { return openUserId; }
    public void setOpenUserId(String openUserId) { this.openUserId = openUserId; }
    public String getLocalStatus() { return localStatus; }
    public void setLocalStatus(String localStatus) { this.localStatus = localStatus; }
    public String getBindingStatus() { return bindingStatus; }
    public void setBindingStatus(String bindingStatus) { this.bindingStatus = bindingStatus; }
    public String getIdentStatus() { return identStatus; }
    public void setIdentStatus(String identStatus) { this.identStatus = identStatus; }
    public String getIdentProcessStatus() { return identProcessStatus; }
    public void setIdentProcessStatus(String identProcessStatus) { this.identProcessStatus = identProcessStatus; }
    public String getAuthScopes() { return authScopes; }
    public void setAuthScopes(String authScopes) { this.authScopes = authScopes; }
    public String getIdentMethod() { return identMethod; }
    public void setIdentMethod(String identMethod) { this.identMethod = identMethod; }
    public String getVerifiedName() { return verifiedName; }
    public void setVerifiedName(String verifiedName) { this.verifiedName = verifiedName; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public LocalDateTime getIdentSubmittedAt() { return identSubmittedAt; }
    public void setIdentSubmittedAt(LocalDateTime identSubmittedAt) { this.identSubmittedAt = identSubmittedAt; }
    public LocalDateTime getIdentVerifiedAt() { return identVerifiedAt; }
    public void setIdentVerifiedAt(LocalDateTime identVerifiedAt) { this.identVerifiedAt = identVerifiedAt; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
