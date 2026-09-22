package com.tradepass.module.settlement.dal.dataobject.reconciliation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableField;

@TableName("reconciliation_entry")
public class ReconciliationEntryDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyAId;
    private Long companyBId;
    private Long contractId;
    private String sourceType;
    private Long sourceId;
    private LocalDate businessDate;
    private String documentNo;
    private BigDecimal amount;
    private Long supplierCompanyId;
    private Long buyerCompanyId;
    private Long issuerCompanyId;
    private Long approvedBy;
    private LocalDateTime approvedAt;
    private Long reversalOfId;
    private Long actionRequestId;
    private LocalDateTime createdAt;
    @TableField(exist = false)
    private String contractNo;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyAId() { return companyAId; }
    public void setCompanyAId(Long companyAId) { this.companyAId = companyAId; }
    public Long getCompanyBId() { return companyBId; }
    public void setCompanyBId(Long companyBId) { this.companyBId = companyBId; }
    public Long getContractId() { return contractId; }
    public void setContractId(Long contractId) { this.contractId = contractId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public LocalDate getBusinessDate() { return businessDate; }
    public void setBusinessDate(LocalDate businessDate) { this.businessDate = businessDate; }
    public String getDocumentNo() { return documentNo; }
    public void setDocumentNo(String documentNo) { this.documentNo = documentNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Long getSupplierCompanyId() { return supplierCompanyId; }
    public void setSupplierCompanyId(Long supplierCompanyId) { this.supplierCompanyId = supplierCompanyId; }
    public Long getBuyerCompanyId() { return buyerCompanyId; }
    public void setBuyerCompanyId(Long buyerCompanyId) { this.buyerCompanyId = buyerCompanyId; }
    public Long getIssuerCompanyId() { return issuerCompanyId; }
    public void setIssuerCompanyId(Long issuerCompanyId) { this.issuerCompanyId = issuerCompanyId; }
    public Long getApprovedBy() { return approvedBy; }
    public void setApprovedBy(Long approvedBy) { this.approvedBy = approvedBy; }
    public LocalDateTime getApprovedAt() { return approvedAt; }
    public void setApprovedAt(LocalDateTime approvedAt) { this.approvedAt = approvedAt; }
    public Long getReversalOfId() { return reversalOfId; }
    public void setReversalOfId(Long reversalOfId) { this.reversalOfId = reversalOfId; }
    public Long getActionRequestId() { return actionRequestId; }
    public void setActionRequestId(Long actionRequestId) { this.actionRequestId = actionRequestId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getContractNo() { return contractNo; }
    public void setContractNo(String contractNo) { this.contractNo = contractNo; }
}
