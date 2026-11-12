package com.tradepass.module.identity.dal.mysql.company;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyReferralDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CompanyReferralMapper extends BaseMapper<CompanyReferralDO> {
    @Insert("""
            INSERT INTO company_referral (
                invite_id, invited_company_id, invited_credit_code,
                inviter_company_id, inviter_credit_code, confirmed_at
            ) VALUES (#{inviteId}, #{invitedCompanyId}, #{invitedCreditCode},
                      #{inviterCompanyId}, #{inviterCreditCode}, CURRENT_TIMESTAMP(6))
            """)
    int insertConfirmed(@Param("inviteId") long inviteId,
            @Param("invitedCompanyId") long invitedCompanyId, @Param("invitedCreditCode") String invitedCreditCode,
            @Param("inviterCompanyId") long inviterCompanyId, @Param("inviterCreditCode") String inviterCreditCode);

    @Select("SELECT COUNT(*) FROM company_referral WHERE inviter_credit_code = #{inviterCreditCode}")
    long countConfirmed(@Param("inviterCreditCode") String inviterCreditCode);
}
