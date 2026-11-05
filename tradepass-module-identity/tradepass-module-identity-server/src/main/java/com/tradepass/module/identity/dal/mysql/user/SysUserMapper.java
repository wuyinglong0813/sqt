package com.tradepass.module.identity.dal.mysql.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.user.SysUserDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUserDO> {
    @Select("SELECT id FROM sys_user WHERE id = #{userId} FOR UPDATE")
    Long lockForMembership(@Param("userId") Long userId);
}
