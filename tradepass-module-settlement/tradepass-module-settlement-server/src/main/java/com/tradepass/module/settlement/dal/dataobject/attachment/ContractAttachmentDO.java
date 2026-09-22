package com.tradepass.module.settlement.dal.dataobject.attachment;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableField;

@TableName("contract_attachment")
public class ContractAttachmentDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long contractId;
    private Long uploaderCompanyId;
    private Long recipientCompanyId;
    private String category;
    private String status;
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
    private LocalDate voucherDate;
    private BigDecimal voucherAmount;
    private String invoiceNo;
    private LocalDate invoiceDate;
    private BigDecimal invoiceAmount;
    private Long createdBy;
    private LocalDateTime createdAt;
    private Long confirmedBy;
    private String signerName;
    private LocalDateTime signedAt;
    private LocalDateTime confirmedAt;
    private String rejectedReason;
    private Long deletedBy;
    private LocalDateTime deletedAt;
    private String signatureOriginalName;
    private String signatureContentType;
    private Long signatureFileSize;
    private byte[] signatureData;
    private String signatureSha256;
    private String signatureStorageProvider;
    private String signatureStorageBucket;
    private String signatureObjectKey;
    private String signatureObjectVersionId;
    private String signatureEtag;
    private String signatureEncryptionAlgorithm;
    @TableField(exist = false)
    private String uploaderCompanyName;
    @TableField(exist = false)
    private String uploaderName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public Long getUploaderCompanyId() { return uploaderCompanyId; }
    public void setUploaderCompanyId(Long uploaderCompanyId) { this.uploaderCompanyId = uploaderCompanyId; }
    public Long getRecipientCompanyId() { return recipientCompanyId; }
    public void setRecipientCompanyId(Long recipientCompanyId) { this.recipientCompanyId = recipientCompanyId; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
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
    public LocalDate getVoucherDate() { return voucherDate; }
    public void setVoucherDate(LocalDate voucherDate) { this.voucherDate = voucherDate; }
    public BigDecimal getVoucherAmount() { return voucherAmount; }
    public void setVoucherAmount(BigDecimal voucherAmount) { this.voucherAmount = voucherAmount; }
    public String getInvoiceNo() { return invoiceNo; }
    public void setInvoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; }
    public LocalDate getInvoiceDate() { return invoiceDate; }
    public void setInvoiceDate(LocalDate invoiceDate) { this.invoiceDate = invoiceDate; }
    public BigDecimal getInvoiceAmount() { return invoiceAmount; }
    public void setInvoiceAmount(BigDecimal invoiceAmount) { this.invoiceAmount = invoiceAmount; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getConfirmedBy() { return confirmedBy; }
    public void setConfirmedBy(Long confirmedBy) { this.confirmedBy = confirmedBy; }
    public String getSignerName() { return signerName; }
    public void setSignerName(String signerName) { this.signerName = signerName; }
    public LocalDateTime getSignedAt() { return signedAt; }
    public void setSignedAt(LocalDateTime signedAt) { this.signedAt = signedAt; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public String getSignatureOriginalName() { return signatureOriginalName; }
    public void setSignatureOriginalName(String signatureOriginalName) { this.signatureOriginalName = signatureOriginalName; }
    public String getSignatureContentType() { return signatureContentType; }
    public void setSignatureContentType(String signatureContentType) { this.signatureContentType = signatureContentType; }
    public Long getSignatureFileSize() { return signatureFileSize; }
    public void setSignatureFileSize(Long signatureFileSize) { this.signatureFileSize = signatureFileSize; }
    public byte[] getSignatureData() { return signatureData; }
    public void setSignatureData(byte[] signatureData) { this.signatureData = signatureData; }
    public String getSignatureSha256() { return signatureSha256; }
    public void setSignatureSha256(String signatureSha256) { this.signatureSha256 = signatureSha256; }
    public String getSignatureStorageProvider() { return signatureStorageProvider; }
    public void setSignatureStorageProvider(String signatureStorageProvider) { this.signatureStorageProvider = signatureStorageProvider; }
    public String getSignatureStorageBucket() { return signatureStorageBucket; }
    public void setSignatureStorageBucket(String signatureStorageBucket) { this.signatureStorageBucket = signatureStorageBucket; }
    public String getSignatureObjectKey() { return signatureObjectKey; }
    public void setSignatureObjectKey(String signatureObjectKey) { this.signatureObjectKey = signatureObjectKey; }
    public String getSignatureObjectVersionId() { return signatureObjectVersionId; }
    public void setSignatureObjectVersionId(String signatureObjectVersionId) { this.signatureObjectVersionId = signatureObjectVersionId; }
    public String getSignatureEtag() { return signatureEtag; }
    public void setSignatureEtag(String signatureEtag) { this.signatureEtag = signatureEtag; }
    public String getSignatureEncryptionAlgorithm() { return signatureEncryptionAlgorithm; }
    public void setSignatureEncryptionAlgorithm(String signatureEncryptionAlgorithm) { this.signatureEncryptionAlgorithm = signatureEncryptionAlgorithm; }
    public String getUploaderCompanyName() { return uploaderCompanyName; }
    public void setUploaderCompanyName(String uploaderCompanyName) { this.uploaderCompanyName = uploaderCompanyName; }
    public String getUploaderName() { return uploaderName; }
    public void setUploaderName(String uploaderName) { this.uploaderName = uploaderName; }
}
