package com.tradepass.module.identity.dal.dataobject.counterparty;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("counterparty_relation")
public class CounterpartyRelationEntityDO {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long companyId;
    private Long counterpartyCompanyId;
    private String counterpartyCompanyName;
    private String relationType;
    private String status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getCounterpartyCompanyId() { return counterpartyCompanyId; }
    public void setCounterpartyCompanyId(Long counterpartyCompanyId) { this.counterpartyCompanyId = counterpartyCompanyId; }
    public String getCounterpartyCompanyName() { return counterpartyCompanyName; }
    public void setCounterpartyCompanyName(String counterpartyCompanyName) { this.counterpartyCompanyName = counterpartyCompanyName; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
