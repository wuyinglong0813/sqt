package com.tradepass.module.identity.dal.mysql.permission;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradepass.module.identity.dal.dataobject.permission.RoleDefDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RoleDefMapper extends BaseMapper<RoleDefDO> {
}
