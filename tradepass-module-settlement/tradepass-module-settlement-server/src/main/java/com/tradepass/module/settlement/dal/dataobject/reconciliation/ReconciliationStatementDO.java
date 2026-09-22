package com.tradepass.module.settlement.dal.dataobject.reconciliation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableField;

@TableName("reconciliation_statement")
public class ReconciliationStatementDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long issuerCompanyId;
    private Long counterpartyCompanyId;
    private String statementPeriod;
    private String originalName;
    private String contentType;
    private Long fileSize;
    private byte[] fileData;
    private String sha256;
    private String storageProvider;
    private String storageBucket;
    private String objectKey;
    private String objectVersionId;
    private String etag;
    private String encryptionAlgorithm;
    private String remark;
    private Long createdBy;
    private LocalDateTime createdAt;
    @TableField(exist = false)
    private String issuerCompanyName;
    @TableField(exist = false)
    private String counterpartyName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getIssuerCompanyId() { return issuerCompanyId; }
    public void setIssuerCompanyId(Long issuerCompanyId) { this.issuerCompanyId = issuerCompanyId; }
    public Long getCounterpartyCompanyId() { return counterpartyCompanyId; }
    public void setCounterpartyCompanyId(Long counterpartyCompanyId) { this.counterpartyCompanyId = counterpartyCompanyId; }
    public String getStatementPeriod() { return statementPeriod; }
    public void setStatementPeriod(String statementPeriod) { this.statementPeriod = statementPeriod; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public byte[] getFileData() { return fileData; }
    public void setFileData(byte[] fileData) { this.fileData = fileData; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getStorageProvider() { return storageProvider; }
    public void setStorageProvider(String storageProvider) { this.storageProvider = storageProvider; }
    public String getStorageBucket() { return storageBucket; }
    public void setStorageBucket(String storageBucket) { this.storageBucket = storageBucket; }
    public String getObjectKey() { return objectKey; }
    public void setObjectKey(String objectKey) { this.objectKey = objectKey; }
    public String getObjectVersionId() { return objectVersionId; }
    public void setObjectVersionId(String objectVersionId) { this.objectVersionId = objectVersionId; }
    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getIssuerCompanyName() { return issuerCompanyName; }
    public void setIssuerCompanyName(String issuerCompanyName) { this.issuerCompanyName = issuerCompanyName; }
    public String getCounterpartyName() { return counterpartyName; }
    public void setCounterpartyName(String counterpartyName) { this.counterpartyName = counterpartyName; }
}
