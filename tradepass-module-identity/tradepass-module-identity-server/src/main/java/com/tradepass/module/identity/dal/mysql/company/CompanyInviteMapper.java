package com.tradepass.module.identity.dal.mysql.company;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.company.CompanyInviteDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CompanyInviteMapper extends BaseMapper<CompanyInviteDO> {
    @Update("""
        UPDATE company_invite
        SET used = 1, used_by = #{userId}
        WHERE id = #{id} AND used = 0 AND expires_at > NOW()
        """)
    int claim(@Param("id") Long id, @Param("userId") Long userId);
}
