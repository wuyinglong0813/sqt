package com.tradepass.module.identity.dal.dataobject.company;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("company_referral")
public class CompanyReferralDO {
    @TableId(value = "invite_id", type = IdType.INPUT)
    private Long inviteId;
    private Long inviterCompanyId;
    private String inviterCreditCode;
    private Long invitedCompanyId;
    private String invitedCreditCode;
    private LocalDateTime confirmedAt;

    public Long getInviteId() { return inviteId; }
    public void setInviteId(Long value) { this.inviteId = value; }
    public Long getInviterCompanyId() { return inviterCompanyId; }
    public void setInviterCompanyId(Long value) { this.inviterCompanyId = value; }
    public String getInviterCreditCode() { return inviterCreditCode; }
    public void setInviterCreditCode(String value) { this.inviterCreditCode = value; }
    public Long getInvitedCompanyId() { return invitedCompanyId; }
    public void setInvitedCompanyId(Long value) { this.invitedCompanyId = value; }
    public String getInvitedCreditCode() { return invitedCreditCode; }
    public void setInvitedCreditCode(String value) { this.invitedCreditCode = value; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime value) { this.confirmedAt = value; }
}
