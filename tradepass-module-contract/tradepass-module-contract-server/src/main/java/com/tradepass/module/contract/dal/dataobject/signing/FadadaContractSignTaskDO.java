package com.tradepass.module.contract.dal.dataobject.signing;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("fadada_contract_sign_task")
public class FadadaContractSignTaskDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contractId;
    private Integer versionNo;
    private String signTaskId;
    private String abolishedSignTaskId;
    private String sourceFileId;
    private String docId;
    private String sourceSha256;
    private String contractSnapshot;
    private String providerStatus;
    private Long initiatorCompanyId;
    private Long counterpartyCompanyId;
    private String initiatorActorId;
    private String counterpartyActorId;
    private String initiatorSignStatus;
    private String counterpartySignStatus;
    private String lastError;
    private LocalDateTime preparedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime archivedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public String getSignTaskId() { return signTaskId; }
    public void setSignTaskId(String signTaskId) { this.signTaskId = signTaskId; }
    public String getAbolishedSignTaskId() { return abolishedSignTaskId; }
    public void setAbolishedSignTaskId(String value) { this.abolishedSignTaskId = value; }
    public String getSourceFileId() { return sourceFileId; }
    public void setSourceFileId(String sourceFileId) { this.sourceFileId = sourceFileId; }
    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public String getSourceSha256() { return sourceSha256; }
    public void setSourceSha256(String sourceSha256) { this.sourceSha256 = sourceSha256; }
    public String getContractSnapshot() { return contractSnapshot; }
    public void setContractSnapshot(String value) { this.contractSnapshot = value; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String providerStatus) { this.providerStatus = providerStatus; }
    public Long getInitiatorCompanyId() { return initiatorCompanyId; }
    public void setInitiatorCompanyId(Long value) { this.initiatorCompanyId = value; }
    public Long getCounterpartyCompanyId() { return counterpartyCompanyId; }
    public void setCounterpartyCompanyId(Long value) { this.counterpartyCompanyId = value; }
    public String getInitiatorActorId() { return initiatorActorId; }
    public void setInitiatorActorId(String value) { this.initiatorActorId = value; }
    public String getCounterpartyActorId() { return counterpartyActorId; }
    public void setCounterpartyActorId(String value) { this.counterpartyActorId = value; }
    public String getInitiatorSignStatus() { return initiatorSignStatus; }
    public void setInitiatorSignStatus(String value) { this.initiatorSignStatus = value; }
    public String getCounterpartySignStatus() { return counterpartySignStatus; }
    public void setCounterpartySignStatus(String value) { this.counterpartySignStatus = value; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public LocalDateTime getPreparedAt() { return preparedAt; }
    public void setPreparedAt(LocalDateTime value) { this.preparedAt = value; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime value) { this.finishedAt = value; }
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime value) { this.archivedAt = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime value) { this.createdAt = value; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime value) { this.updatedAt = value; }
}
