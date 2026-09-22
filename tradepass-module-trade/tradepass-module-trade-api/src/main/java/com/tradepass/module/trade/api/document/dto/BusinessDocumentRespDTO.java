package com.tradepass.module.trade.api.document.dto;


import java.time.LocalDateTime;

public class BusinessDocumentRespDTO {

    private Long id;
    private Long companyId;
    private Long recipientCompanyId;
    private Long supplierCompanyId;
    private Long buyerCompanyId;
    private Long contractId;
    private String documentType;
    private String sourceType;
    private String status;
    private String documentNo;
    private Long templateId;
    private String templateName;
    private String content;
    private Long createdBy;
    private Long acknowledgedBy;
    private LocalDateTime acknowledgedAt;
    private String rejectedReason;
    private Long outboundWarehouseId;
    private Long inboundWarehouseId;
    private Long deletedBy;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getRecipientCompanyId() { return recipientCompanyId; }
    public void setRecipientCompanyId(Long recipientCompanyId) { this.recipientCompanyId = recipientCompanyId; }
    public Long getSupplierCompanyId() { return supplierCompanyId; }
    public void setSupplierCompanyId(Long supplierCompanyId) { this.supplierCompanyId = supplierCompanyId; }
    public Long getBuyerCompanyId() { return buyerCompanyId; }
    public void setBuyerCompanyId(Long buyerCompanyId) { this.buyerCompanyId = buyerCompanyId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getDocumentType() { return documentType; }
    public void setDocumentType(String documentType) { this.documentType = documentType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDocumentNo() { return documentNo; }
    public void setDocumentNo(String documentNo) { this.documentNo = documentNo; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Long getAcknowledgedBy() { return acknowledgedBy; }
    public void setAcknowledgedBy(Long acknowledgedBy) { this.acknowledgedBy = acknowledgedBy; }
    public LocalDateTime getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(LocalDateTime acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }
    public String getRejectedReason() { return rejectedReason; }
    public void setRejectedReason(String rejectedReason) { this.rejectedReason = rejectedReason; }
    public Long getOutboundWarehouseId() { return outboundWarehouseId; }
    public void setOutboundWarehouseId(Long outboundWarehouseId) { this.outboundWarehouseId = outboundWarehouseId; }
    public Long getInboundWarehouseId() { return inboundWarehouseId; }
    public void setInboundWarehouseId(Long inboundWarehouseId) { this.inboundWarehouseId = inboundWarehouseId; }
    public Long getDeletedBy() { return deletedBy; }
    public void setDeletedBy(Long deletedBy) { this.deletedBy = deletedBy; }
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
