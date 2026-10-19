package com.tradepass.module.identity.dal.dataobject.fadada;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** The deterministic primary key is companyId:userId; mutations use the leased queue SQL. */
@TableName("fadada_signing_member_sync")
public class FadadaSigningMemberSyncDO {
    @TableId(type = IdType.INPUT)
    private String id;
    private Long companyId;
    private Long userId;
    private String status;
    private Integer lastErrorCode;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Long getCompanyId() { return companyId; }
    public void setCompanyId(Long companyId) { this.companyId = companyId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getLastErrorCode() { return lastErrorCode; }
    public void setLastErrorCode(Integer lastErrorCode) { this.lastErrorCode = lastErrorCode; }
}
